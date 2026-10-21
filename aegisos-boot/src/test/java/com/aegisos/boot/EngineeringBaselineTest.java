package com.aegisos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EngineeringBaselineTest.TestEndpoints.class)
@ExtendWith(OutputCaptureExtension.class)
class EngineeringBaselineTest {
  @LocalServerPort private int port;
  @Autowired private ObjectMapper mapper;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  void healthIsMinimalAndDefaultManagementAndDocsAreClosed() throws Exception {
    HttpResponse<String> health = get("/actuator/health");
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(health.body())).isEqualTo(mapper.readTree("{\"status\":\"UP\"}"));
    assertThat(health.headers().firstValue("X-Request-ID")).isPresent();
    for (String path :
        List.of(
            "/actuator/env",
            "/actuator/configprops",
            "/actuator/heapdump",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/swagger-ui/index.html")) {
      assertThat(get(path).statusCode()).as(path).isEqualTo(404);
    }
  }

  @Test
  void acceptsValidCorrelationIdAndIncludesItInJsonLogs(CapturedOutput output) throws Exception {
    String id = "test-" + UUID.randomUUID();
    HttpResponse<String> result =
        client.send(
            request("/_test/ok").header("X-Request-ID", id).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.headers().firstValue("X-Request-ID")).contains(id);
    assertThat(mapper.readTree(result.body()).get("requestId").asText()).isEqualTo(id);
    assertThat(output.getOut())
        .contains("\"requestId\":\"" + id + "\"")
        .contains("\"event\":\"http_request_completed\"");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "invalid/id",
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-too-long"
      })
  void replacesUntrustedCorrelationId(String invalidId) throws Exception {
    HttpResponse<String> result =
        client.send(
            request("/_test/ok").header("X-Request-ID", invalidId).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String generated = result.headers().firstValue("X-Request-ID").orElseThrow();
    assertThat(UUID.fromString(generated)).isNotNull();
    assertThat(generated).isNotEqualTo(invalidId);
  }

  @Test
  void generatesDifferentIdsForUncorrelatedRequests() throws Exception {
    assertThat(get("/_test/ok").headers().firstValue("X-Request-ID"))
        .isNotEqualTo(get("/_test/ok").headers().firstValue("X-Request-ID"));
  }

  @Test
  void concurrentRequestsDoNotShareCorrelationIds() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 12)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            String id = "concurrent-" + UUID.randomUUID();
                            var result =
                                client.send(
                                    request("/_test/ok").header("X-Request-ID", id).GET().build(),
                                    HttpResponse.BodyHandlers.ofString());
                            assertThat(mapper.readTree(result.body()).get("requestId").asText())
                                .isEqualTo(id);
                            return id;
                          }))
              .toList();
      for (var future : futures) {
        assertThat(future.get()).startsWith("concurrent-");
      }
    }
  }

  @Test
  void validatesParametersAndRejectsMalformedJsonWithoutEchoingBody(CapturedOutput output)
      throws Exception {
    assertProblem(post("{\"text\":\"\"}"), 400, "INVALID_REQUEST");
    String canary = "private-" + UUID.randomUUID();
    HttpResponse<String> malformed = post("{\"text\":\"" + canary);
    assertProblem(malformed, 400, "INVALID_REQUEST");
    assertThat(malformed.body()).doesNotContain(canary);
    assertThat(output.getAll()).doesNotContain(canary);
  }

  @Test
  void hidesInternalExceptionsAndUnknownResourceDetails(CapturedOutput output) throws Exception {
    String canary = "private-" + UUID.randomUUID();
    HttpResponse<String> failed =
        client.send(
            request("/_test/error").header("X-Test-Secret", canary).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertProblem(failed, 500, "INTERNAL_ERROR");
    assertThat(failed.body()).doesNotContain(canary, "IllegalStateException", "stackTrace");
    assertThat(output.getAll()).doesNotContain(canary);
    assertProblem(get("/unknown-resource"), 404, "NOT_FOUND");
  }

  @Test
  void acceptsJsonExactlyAtTheByteLimit() throws Exception {
    String prefix = "{\"text\":\"";
    String suffix = "\"}";
    assertThat(
            post(prefix + "a".repeat(32768 - prefix.length() - suffix.length()) + suffix)
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void rejectsOversizedFixedLengthAndChunkedJson() throws Exception {
    byte[] body = ("{\"text\":\"" + "a".repeat(32768) + "\"}").getBytes(StandardCharsets.UTF_8);
    assertProblem(
        client.send(
            request("/_test/payload")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()),
        413,
        "PAYLOAD_TOO_LARGE");
    assertProblem(
        client.send(
            request("/_test/payload")
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString()),
        413,
        "PAYLOAD_TOO_LARGE");
  }

  @Test
  void containerRejectsHeadersBeyondItsConfiguredLimit() throws Exception {
    HttpResponse<String> result =
        client.send(
            request("/_test/ok").header("X-Large", "a".repeat(18000)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(400);
  }

  private void assertProblem(HttpResponse<String> result, int status, String code)
      throws Exception {
    assertThat(result.statusCode()).isEqualTo(status);
    assertThat(result.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
    JsonNode error = mapper.readTree(result.body());
    assertThat(error.get("status").asInt()).isEqualTo(status);
    assertThat(error.get("code").asText()).isEqualTo(code);
    assertThat(error.get("requestId").asText())
        .isEqualTo(result.headers().firstValue("X-Request-ID").orElseThrow());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(10));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String json) throws Exception {
    return client.send(
        request("/_test/payload")
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @org.springframework.boot.test.context.TestComponent
  @RestController
  @RequestMapping("/_test")
  static class TestEndpoints {
    @GetMapping("/ok")
    Map<String, String> ok() {
      return Map.of("requestId", MDC.get("requestId"));
    }

    @GetMapping("/error")
    void error(@RequestHeader("X-Test-Secret") String secret) {
      throw new IllegalStateException(secret);
    }

    @PostMapping("/payload")
    Map<String, Boolean> payload(@Valid @RequestBody Input input) {
      return Map.of("accepted", true);
    }

    record Input(@NotBlank String text) {}
  }
}

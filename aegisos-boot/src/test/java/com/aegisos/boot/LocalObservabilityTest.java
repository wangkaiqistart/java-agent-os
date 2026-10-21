package com.aegisos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@ActiveProfiles({"dev", "metrics"})
@AutoConfigureObservability
class LocalObservabilityTest {
  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void prometheusIsOnlyAvailableOnTheSeparateLoopbackManagementListener() throws Exception {
    assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
    HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body()).contains("jvm_memory_used_bytes", "process_uptime_seconds");
    assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
  }

  @Test
  void developmentDocsAreAvailableWithoutCreatingBusinessEndpoints() throws Exception {
    HttpResponse<String> docs = get(port, "/v3/api-docs");
    assertThat(docs.statusCode()).isEqualTo(200);
    assertThat(docs.body()).contains("\"openapi\"").doesNotContain("/_test");
  }

  private HttpResponse<String> get(int targetPort, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

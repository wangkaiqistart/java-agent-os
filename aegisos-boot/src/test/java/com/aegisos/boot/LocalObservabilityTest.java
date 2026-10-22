package com.aegisos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@ActiveProfiles({"dev", "metrics"})
@AutoConfigureMetrics
class LocalObservabilityTest {
  /** 工程回归使用独立新库，不能触碰个人工作区或偷偷迁移已有 V1。 */
  @org.springframework.test.context.DynamicPropertySource
  static void isolatedAudit(org.springframework.test.context.DynamicPropertyRegistry registry)
      throws java.io.IOException {
    var workspace = java.nio.file.Files.createTempDirectory("aegisos-baseline-");
    registry.add("aegisos.audit.path", () -> workspace.resolve("audit.db").toString());
    registry.add("aegisos.workspace-root", workspace::toString);
  }

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

  @Test
  void developmentSwaggerAssetsAndConfigurationAreServed() throws Exception {
    HttpResponse<String> page = get(port, "/swagger-ui/index.html");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("swagger-ui-bundle.js", "swagger-ui.css");

    HttpResponse<String> bundle = get(port, "/swagger-ui/swagger-ui-bundle.js");
    assertThat(bundle.statusCode()).isEqualTo(200);
    assertThat(bundle.body()).contains("SwaggerUIBundle");
    assertThat(get(port, "/swagger-ui/swagger-ui.css").statusCode()).isEqualTo(200);

    HttpResponse<String> initializer = get(port, "/swagger-ui/swagger-initializer.js");
    assertThat(initializer.statusCode()).isEqualTo(200);
    assertThat(initializer.body()).contains("/v3/api-docs/swagger-config");
    HttpResponse<String> config = get(port, "/v3/api-docs/swagger-config");
    assertThat(config.statusCode()).isEqualTo(200);
    assertThat(config.body()).contains("/v3/api-docs");
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

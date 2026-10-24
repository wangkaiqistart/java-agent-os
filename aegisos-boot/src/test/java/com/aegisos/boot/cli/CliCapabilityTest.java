package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.AegisOsApplication;
import com.aegisos.cli.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliCapabilityTest {
  @TempDir Path root;

  @Test
  void statusIsTruthfulAndGatewayIsExplicitlyDisabledWithoutDatabase() throws Exception {
    new InitCommand().run(root);
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit =
        AegisOsApplication.dispatch(
            new String[] {"status", "--workspace", root.toString()},
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(out),
            new PrintStream(err));
    assertThat(exit).isEqualTo(2);
    assertThat(out.toString()).contains("DATABASE_NOT_INITIALIZED", "CONFIGURED");
    assertThat(
            AegisOsApplication.dispatch(
                new String[] {"gateway", "--workspace", root.toString()},
                System.in,
                new PrintStream(out),
                new PrintStream(err)))
        .isEqualTo(6);
    assertThat(Files.exists(root.resolve(".aegisos/aegisos.db"))).isFalse();
  }

  @Test
  void serveUsesLocalApprovedWorkspaceAndClosesItsDatabaseLock() throws Exception {
    new InitCommand().run(root);
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    var parsed =
        CliArguments.parse(
            new String[] {
              "serve", "--workspace", root.toString(), "--port", Integer.toString(port)
            });
    try (var context = new CliRuntimeLauncher().openServe(parsed)) {
      assertThat(context).isNotNull();
      var web = (org.springframework.boot.web.server.context.WebServerApplicationContext) context;
      assertThat(web.getWebServer().getPort()).isEqualTo(port);
      var request =
          java.net.http.HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + port + "/actuator/health"))
              .GET()
              .build();
      assertThat(
              java.net.http.HttpClient.newHttpClient()
                  .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(200);
      assertThat(context.getBeansOfType(com.aegisos.core.agent.AgentService.class)).isEmpty();
    }
    try (var lock =
        com.aegisos.storage.runtime.RuntimeDatabaseLock.acquire(
            root.resolve(".aegisos/aegisos.db"))) {
      assertThat(lock).isNotNull();
    }
  }
}

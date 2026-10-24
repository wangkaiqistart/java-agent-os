package com.aegisos.tool;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.ExternalOutcome;
import com.aegisos.core.tool.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HttpGetToolTest {
  @Test
  void successErrorsAndRedirectNeverResendAndPermissionCannotBeReused() throws Exception {
    for (int code : new int[] {200, 302, 400, 503}) {
      var requests = new AtomicInteger();
      var dns = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/weather",
          e -> {
            requests.incrementAndGet();
            e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            e.getResponseHeaders().set("Location", "/weather");
            var b = "{\"temperature\":20}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(code, b.length);
            try (var out = e.getResponseBody()) {
              out.write(b);
            }
          });
      server.start();
      try {
        String url = "http://localhost:" + server.getAddress().getPort() + "/weather";
        var policy =
            NetworkPolicy.defaults(
                List.of(
                    new NetworkPolicy.Target(
                        "http", "localhost", server.getAddress().getPort(), "/weather", Set.of())));
        var sandbox =
            WhitelistSandbox.isolatedLoopback(
                policy,
                h -> {
                  dns.incrementAndGet();
                  return new InetAddress[] {InetAddress.getLoopbackAddress()};
                });
        var c = SandboxPolicyTest.context();
        var args = SandboxPolicyTest.args(url);
        try (var p = (HttpGetPermission) sandbox.authorize(c, args)) {
          var result = new HttpGetTool().execute(c, args, p);
          assertThat(result.success()).isEqualTo(code == 200);
          assertThat(result.externalOutcome()).isEqualTo(ExternalOutcome.RESPONDED);
          if (code == 200)
            assertThat(result.data())
                .contains("temperature", "statusCode", "mediaType")
                .doesNotContain(url);
          assertThatThrownBy(() -> new HttpGetTool().execute(c, args, p))
              .isInstanceOf(AgentException.class);
        }
        assertThat(requests).hasValue(1);
        assertThat(dns).hasValue(1);
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void oversizedBinaryCompressedAndInvalidUtf8ResponsesFailWithoutRetry() throws Exception {
    for (int kind = 0; kind < 4; kind++) {
      int k = kind;
      var count = new AtomicInteger();
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/weather",
          e -> {
            count.incrementAndGet();
            e.getResponseHeaders()
                .set(
                    "Content-Type",
                    k == 1 ? "application/octet-stream" : "text/plain; charset=utf-8");
            if (k == 2) e.getResponseHeaders().set("Content-Encoding", "gzip");
            byte[] b =
                k == 3
                    ? new byte[] {(byte) 0xff}
                    : "a".repeat(k == 0 ? 8193 : 10).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, b.length);
            try (var out = e.getResponseBody()) {
              out.write(b);
            }
          });
      server.start();
      try {
        var policy =
            NetworkPolicy.defaults(
                List.of(
                    new NetworkPolicy.Target(
                        "http", "localhost", server.getAddress().getPort(), "/weather", Set.of())));
        var args =
            SandboxPolicyTest.args(
                "http://localhost:" + server.getAddress().getPort() + "/weather");
        var c = SandboxPolicyTest.context();
        try (var p =
            (HttpGetPermission)
                WhitelistSandbox.isolatedLoopback(
                        policy, h -> new InetAddress[] {InetAddress.getLoopbackAddress()})
                    .authorize(c, args)) {
          var result = new HttpGetTool().execute(c, args, p);
          assertThat(result.success()).isFalse();
          assertThat(result.data()).isNull();
          assertThat(result.externalOutcome()).isEqualTo(ExternalOutcome.RESPONDED);
        }
        assertThat(count).hasValue(1);
      } finally {
        server.stop(0);
      }
    }
  }
}

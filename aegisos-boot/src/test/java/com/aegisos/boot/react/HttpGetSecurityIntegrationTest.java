package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.ExternalOutcome;
import com.aegisos.core.tool.*;
import com.aegisos.tool.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HttpGetSecurityIntegrationTest {
  ToolExecutionContext context() {
    var p =
        new Profile(
            "p",
            null,
            null,
            new ProviderSelection("p", "m", null),
            List.of("http_get"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    return new ToolExecutionContext(
        new ProcessingHandle(
            UUID.randomUUID(), UUID.randomUUID().toString(), "p", UUID.randomUUID(), 0),
        p,
        1,
        1);
  }

  @Test
  void responseLimitClosesSocketBeforeWaitingForRemainder() throws Exception {
    var release = new CountDownLatch(1);
    var gets = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/weather",
        e -> {
          gets.incrementAndGet();
          e.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
          e.sendResponseHeaders(200, 0);
          try (var out = e.getResponseBody()) {
            out.write("a".repeat(17).getBytes());
            out.flush();
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          }
        });
    server.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var p =
          new NetworkPolicy(
              List.of(
                  new NetworkPolicy.Target(
                      "http", "localhost", server.getAddress().getPort(), "/weather", Set.of())),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              Duration.ofSeconds(10),
              16);
      var args =
          new ValidatedArguments(
              Map.of("url", "http://localhost:" + server.getAddress().getPort() + "/weather"),
              "{}",
              "a".repeat(64));
      var context = context();
      try (var permit =
          (HttpGetPermission)
              WhitelistSandbox.isolatedLoopback(
                      p, h -> new InetAddress[] {InetAddress.getLoopbackAddress()})
                  .authorize(context, args)) {
        var future = executor.submit(() -> new HttpGetTool().execute(context, args, permit));
        try {
          var result = future.get(2, TimeUnit.SECONDS);
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.TOOL_RESPONSE_LIMIT);
          assertThat(result.externalOutcome()).isEqualTo(ExternalOutcome.RESPONDED);
        } finally {
          release.countDown();
        }
      }
      assertThat(gets).hasValue(1);
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void timedOutDnsCannotConnectLaterAndGlobalExecutionPermitsAreReleased() throws Exception {
    var dns = new AtomicInteger();
    var p =
        new NetworkPolicy(
            List.of(new NetworkPolicy.Target("https", "example.org", 443, "/weather", Set.of())),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(50),
            16);
    var sandbox =
        new WhitelistSandbox(
            p,
            h -> {
              dns.incrementAndGet();
              Thread.sleep(1000);
              return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            });
    var args =
        new ValidatedArguments(Map.of("url", "https://example.org/weather"), "{}", "a".repeat(64));
    for (int i = 0; i < 10; i++)
      assertThatThrownBy(() -> sandbox.authorize(context(), args)).hasMessage("TOOL_TIMEOUT");
    assertThat(dns).hasValue(10);
  }
}

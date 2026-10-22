package com.aegisos.boot.config;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.OpenAiClientFactory;
import com.aegisos.provider.config.*;
import com.aegisos.provider.service.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

final class BootProviderFixture implements AutoCloseable {
  final HttpServer server;
  final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final AtomicInteger calls = new AtomicInteger();
  final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
  final List<String> auth = new CopyOnWriteArrayList<>();

  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange, int n) throws Exception;
  }

  BootProviderFixture(Handler handler) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/",
        e -> {
          int n = calls.incrementAndGet();
          auth.add(e.getRequestHeaders().getFirst("Authorization"));
          bodies.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            handler.handle(e, n);
          } catch (Exception ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
  }

  URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  static String text() {
    return "{\"id\":\"s\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}";
  }

  static void reply(HttpExchange e, int code, String text) throws IOException {
    byte[] b = text.getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(code, b.length);
    e.getResponseBody().write(b);
  }

  AnnotationConfigApplicationContext manualContext(Path db) {
    var c = new AnnotationConfigApplicationContext();
    c.getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("aegisos.audit.path", db.toString())));
    c.register(AuditStorageConfiguration.class);
    c.registerBean(
        ProviderService.class,
        () -> {
          var d =
              new ProviderDefinition(
                  "p",
                  "openai-compatible",
                  Set.of("m"),
                  uri(),
                  "env:TEST_KEY",
                  0,
                  Duration.ofSeconds(1),
                  Duration.ofMillis(200));
          var adapter = new OpenAiClientFactory().create(d, UUID.randomUUID().toString());
          c.registerBean(
              "manualAdapter",
              com.aegisos.provider.adapter.ProviderAdapter.class,
              () -> adapter,
              bd -> bd.setDestroyMethodName("close"));
          return new DefaultProviderService(
              List.of(new ProviderRegistration(d, adapter, null)),
              c.getBean(LlmCallAuditStore.class),
              () -> {});
        });
    c.refresh();
    return c;
  }

  AnnotationConfigApplicationContext productionContext(Path db) {
    return productionContext(db, db.getParent());
  }

  AnnotationConfigApplicationContext productionContext(Path db, Path workspace) {
    var c = new AnnotationConfigApplicationContext();
    c.getEnvironment().setActiveProfiles("test");
    c.getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "aegisos.workspace-root",
                    workspace.toString(),
                    "aegisos.audit.path",
                    db.toString(),
                    "aegisos.providers[0].name",
                    "p",
                    "aegisos.providers[0].adapter",
                    "openai-compatible",
                    "aegisos.providers[0].models[0]",
                    "m",
                    "aegisos.providers[0].base-url",
                    uri().toString(),
                    "aegisos.providers[0].credential-ref",
                    "env:KEY",
                    "aegisos.providers[0].read-timeout",
                    "2s")));
    c.registerBean(
        EnvironmentCredentialResolver.class,
        () -> new EnvironmentCredentialResolver(n -> UUID.randomUUID().toString()));
    c.register(ProviderConfiguration.class, AuditStorageConfiguration.class);
    c.refresh();
    return c;
  }

  static List<String> statuses(Path db) throws Exception {
    var states = new ArrayList<String>();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var s = c.createStatement();
        var r = s.executeQuery("SELECT status FROM llm_calls ORDER BY created_at,attempt_no")) {
      while (r.next()) states.add(r.getString(1));
    }
    return states;
  }

  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}

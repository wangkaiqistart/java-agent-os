package com.aegisos.boot.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** 每例独立目录、数据库与回环服务；不允许隐式访问个人工作区或生产网络。 */
public final class ReactRuntimeFixture implements AutoCloseable {
  public final Path root;
  public final Path database;
  public final AtomicInteger httpRequests = new AtomicInteger();
  public java.util.function.Consumer<
          org.springframework.context.annotation.AnnotationConfigApplicationContext>
      customize = c -> {};
  public final AtomicReference<Runnable> beforeReply = new AtomicReference<>(() -> {});
  public final java.util.List<String> modelBodies =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  public int providerRetries;
  public final AtomicInteger physicalSends = new AtomicInteger();
  public final AtomicInteger statusCode = new AtomicInteger(200);
  public final AtomicBoolean failCommit = new AtomicBoolean();
  public final AtomicReference<String> reply = new AtomicReference<>("fixture-response");
  public final Function<String, InetAddress[]> dns =
      host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService serverExecutor =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  public String providerReadTimeout = "60s";
  public final AtomicReference<String> mediaType =
      new AtomicReference<>("text/plain; charset=utf-8");

  public ReactRuntimeFixture() throws IOException {
    root = Files.createTempDirectory("aegisos-react-");
    database = root.resolve("runtime.sqlite");
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          modelBodies.add(
              new String(exchange.getRequestBody().readNBytes(2097152), StandardCharsets.UTF_8));
          httpRequests.incrementAndGet();
          beforeReply.get().run();
          byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", mediaType.get());
          exchange.sendResponseHeaders(statusCode.get(), bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  public URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  public Connection reopen() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + database);
  }

  public void commit(Connection connection) throws SQLException {
    if (failCommit.get()) throw new SQLException("FIXTURE_COMMIT_FAILURE");
    connection.commit();
  }

  public boolean secureReadsSupported() throws IOException {
    try (var stream = Files.newDirectoryStream(root)) {
      boolean supported = stream instanceof SecureDirectoryStream<?>;
      if (Boolean.getBoolean("aegisos.test.require-secure-directory-stream") && !supported)
        throw new AssertionError("SECURE_DIRECTORY_STREAM_REQUIRED");
      Path probe = Files.createTempFile(root, "platform-probe", ".md");
      try {
        Files.writeString(probe, "平台安全读取探针");
        byte[] bytes =
            com.aegisos.core.platform.SecureFileAccess.read(
                root.toRealPath(), probe.toRealPath(), 128);
        if (!new String(bytes, java.nio.charset.StandardCharsets.UTF_8).equals("平台安全读取探针"))
          throw new AssertionError("PLATFORM_READ_REQUIRED");
        return true;
      } finally {
        Files.delete(probe);
      }
    }
  }

  public Runtime openRuntime() throws IOException {
    Path agent = Files.createDirectories(root.resolve(".aegisos/agents/physical"));
    if (!Files.exists(agent.resolve("profile.yml")))
      Files.writeString(
          agent.resolve("profile.yml"),
          "name: p\nprovider: {name: model-provider, model: test-model}\n",
          StandardOpenOption.CREATE_NEW);
    if (!Files.exists(agent.resolve("AGENT.md")))
      Files.writeString(agent.resolve("AGENT.md"), "给出安全简短答复。", StandardOpenOption.CREATE_NEW);
    mediaType.set("application/json");
    reply.set(
        "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"安全回复\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}");
    var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("test");
    var properties = new java.util.HashMap<String, Object>();
    properties.put("aegisos.workspace-root", root.toString());
    properties.put("aegisos.react.max-context-bytes", "262144");
    properties.put("aegisos.audit.path", database.toString());
    properties.put("aegisos.providers[0].name", "model-provider");
    properties.put("aegisos.providers[0].adapter", "openai-compatible");
    properties.put("aegisos.providers[0].models[0]", "test-model");
    properties.put("aegisos.providers[0].base-url", endpoint().toString());
    properties.put("aegisos.providers[0].credential-ref", "env:SYNTHETIC_KEY");
    properties.put("aegisos.providers[0].max-retries", Integer.toString(providerRetries));
    properties.put("aegisos.providers[0].read-timeout", providerReadTimeout);
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new org.springframework.core.env.MapPropertySource("react-test", properties));
    context.registerBean(
        com.aegisos.boot.config.EnvironmentCredentialResolver.class,
        () ->
            new com.aegisos.boot.config.EnvironmentCredentialResolver(
                n -> "synthetic-runtime-credential-123"));
    context.registerBean(
        com.aegisos.provider.adapter.TransportSendObserver.class,
        () -> () -> physicalSends.incrementAndGet());
    context.register(
        com.aegisos.boot.config.ProviderConfiguration.class,
        com.aegisos.storage.audit.AuditStorageConfiguration.class,
        com.aegisos.boot.config.AgentRuntimeConfiguration.class);
    customize.accept(context);
    context.refresh();
    try {
      var memory = context.getBean(com.aegisos.core.memory.MemoryService.class);
      var service = context.getBean(com.aegisos.core.agent.AgentService.class);
      return new Runtime(context, memory, service);
    } catch (RuntimeException | Error e) {
      context.close();
      throw e;
    }
  }

  public record Runtime(
      org.springframework.context.annotation.AnnotationConfigApplicationContext context,
      com.aegisos.core.memory.MemoryService memory,
      com.aegisos.core.agent.AgentService agents)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }

  @Override
  public void close() throws IOException {
    server.stop(0);
    serverExecutor.shutdownNow();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    }
  }
}

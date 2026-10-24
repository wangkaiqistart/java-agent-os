package com.aegisos.boot.react;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.tool.Sandbox;
import com.aegisos.tool.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.json.JsonMapper;

/** 真实 Provider mapper + SQLite + 明确隔离回环许可；不对生产增加私网开关。 */
final class ToolLoopFixture implements AutoCloseable {
  final ReactRuntimeFixture base = new ReactRuntimeFixture();
  final AtomicInteger gets = new AtomicInteger();
  final HttpServer weather;
  final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
  final AtomicReference<String> body = new AtomicReference<>("{\"temperature\":20}");
  final AtomicReference<java.util.function.IntFunction<String>> responses = new AtomicReference<>();

  ToolLoopFixture() throws Exception {
    weather = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    weather.createContext(
        "/",
        e -> {
          gets.incrementAndGet();
          order.add(e.getRequestURI().getPath());
          byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    weather.start();
    int port = weather.getAddress().getPort();
    var policy =
        NetworkPolicy.defaults(
            List.of(
                new NetworkPolicy.Target("http", "localhost", port, "/weather", Set.of()),
                new NetworkPolicy.Target("http", "localhost", port, "/other", Set.of())));
    base.customize =
        c ->
            c.registerBean(
                Sandbox.class,
                () ->
                    WhitelistSandbox.isolatedLoopback(
                        policy, h -> new InetAddress[] {InetAddress.getLoopbackAddress()}));
    base.beforeReply.set(
        () -> {
          var generator = responses.get();
          if (generator != null) base.reply.set(generator.apply(base.httpRequests.get()));
        });
    Path agent = Files.createDirectories(base.root.resolve(".aegisos/agents/physical"));
    Files.writeString(
        agent.resolve("profile.yml"),
        "name: p\nprovider: {name: model-provider, model: test-model}\ntools: [http_get]\n");
  }

  String url(String path) {
    return "http://localhost:" + weather.getAddress().getPort() + path;
  }

  String tool(String id, String path) {
    return "{\"id\":\""
        + id
        + "\",\"type\":\"function\",\"function\":{\"name\":\"http_get\",\"arguments\":"
        + JsonMapper.builder().build().writeValueAsString("{\"url\":\"" + url(path) + "\"}")
        + "}}";
  }

  String response(String... calls) {
    return "{\"id\":\"fixture\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"安全说明\""
        + (calls.length == 0 ? "" : ",\"tool_calls\":[" + String.join(",", calls) + "]")
        + "},\"finish_reason\":\""
        + (calls.length == 0 ? "stop" : "tool_calls")
        + "\"}]}";
  }

  public void close() throws Exception {
    weather.stop(0);
    base.close();
  }
}

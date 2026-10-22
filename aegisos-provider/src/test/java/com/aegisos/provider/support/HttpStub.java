package com.aegisos.provider.support;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HttpStub implements AutoCloseable {
  public final AtomicInteger calls = new AtomicInteger();
  public final List<String> bodies = new CopyOnWriteArrayList<>();
  public final List<String> paths = new CopyOnWriteArrayList<>();
  public final List<String> auth = new CopyOnWriteArrayList<>();
  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @FunctionalInterface
  public interface Handler {
    void handle(HttpExchange e, int n) throws Exception;
  }

  public HttpStub(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext(
        "/",
        e -> {
          int n = calls.incrementAndGet();
          bodies.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          paths.add(e.getRequestURI().getPath());
          auth.add(e.getRequestHeaders().getFirst("Authorization"));
          try {
            handler.handle(e, n);
          } catch (Exception ignored) {
            e.close();
          } finally {
            e.close();
          }
        });
    server.start();
  }

  /** 可控空响应仅用于 loopback 故障注入；捕获内容始终留在测试内存。 */
  public static HttpStub fixed(int status, String response) throws IOException {
    return new HttpStub((exchange, number) -> reply(exchange, status, response));
  }

  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public static void reply(HttpExchange e, int code, String body) throws IOException {
    e.getResponseHeaders().set("Content-Type", "application/json");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
  }

  public static String text() {
    return "{\"id\":\"stub\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"vendor-alias\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}";
  }

  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}

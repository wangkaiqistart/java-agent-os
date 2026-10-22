package com.aegisos.provider.adapter;

import com.aegisos.core.provider.*;
import com.aegisos.provider.config.ProviderDefinition;
import com.aegisos.provider.service.ProviderErrorClassifier;
import com.openai.core.RequestOptions;
import com.openai.core.http.*;
import java.io.*;
import java.util.concurrent.CompletableFuture;
import okhttp3.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 明确关闭连接重发/重定向，完整响应仅在内存中流转；可选仅计数观察器不接收数据，无请求日志。 */
final class SingleRequestHttpClient implements HttpClient {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final OkHttpClient client;
  private final ThreadLocal<Capture> capture = new ThreadLocal<>();

  record Capture(TokenUsage usage, boolean responded) {}

  SingleRequestHttpClient(ProviderDefinition d) {
    this(d, TransportSendObserver.NOOP);
  }

  SingleRequestHttpClient(ProviderDefinition d, TransportSendObserver observer) {
    client =
        new OkHttpClient.Builder()
            .eventListener(
                new okhttp3.EventListener() {
                  @Override
                  public void requestHeadersStart(okhttp3.Call call) {
                    // 连接失败时尚未发送 HTTP 头，计数为0；收到响应或读取超时为1。
                    // 观察器不接收 call，避免暴露端点/凭证；观测故障不能改变发送策略。
                    try {
                      observer.onSend();
                    } catch (RuntimeException ignored) {
                    }
                  }
                })
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(d.connectTimeout())
            .readTimeout(d.readTimeout())
            .writeTimeout(d.readTimeout())
            .callTimeout(d.readTimeout())
            .build();
  }

  void begin() {
    capture.set(new Capture(null, false));
  }

  TokenUsage usage() {
    var c = capture.get();
    return c == null ? null : c.usage();
  }

  boolean responded() {
    var c = capture.get();
    return c != null && c.responded();
  }

  void clear() {
    capture.remove();
  }

  public HttpResponse execute(HttpRequest request, RequestOptions options) {
    try {
      RequestBody body = null;
      var requestBody = request.body();
      if (requestBody != null) {
        String contentType = requestBody.contentType();
        var bytes = new ByteArrayOutputStream();
        try (var b = requestBody) {
          b.writeTo(bytes);
        }
        final byte[] payload = bytes.toByteArray();
        final MediaType mediaType =
            MediaType.parse(contentType == null ? "application/json" : contentType);
        body =
            new RequestBody() {
              @Override
              public MediaType contentType() {
                return mediaType;
              }

              @Override
              public long contentLength() {
                return payload.length;
              }

              // OkHttp 对 503/Retry-After 的 follow-up 独立于连接重试开关，禁止重放请求体。
              @Override
              public boolean isOneShot() {
                return true;
              }

              @Override
              public void writeTo(okio.BufferedSink sink) throws IOException {
                sink.write(payload);
              }
            };
      }
      var b = new Request.Builder().url(request.url()).method(request.method().name(), body);
      request
          .headers()
          .names()
          .forEach(
              name -> request.headers().values(name).forEach(value -> b.addHeader(name, value)));
      try (var response = client.newCall(b.build()).execute()) {
        var responseBody = response.body();
        if (responseBody == null)
          throw ProviderErrorClassifier.error(
              ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
        byte[] bytes = responseBody.bytes();
        capture.set(new Capture(null, true));
        if (response.isSuccessful()) bytes = prepareResponse(bytes);
        var headers = com.openai.core.http.Headers.builder();
        response.headers().toMultimap().forEach(headers::put);
        return new BufferedResponse(response.code(), headers.build(), bytes);
      }
    } catch (IOException | RuntimeException e) {
      throw ProviderErrorClassifier.classify(e, responded());
    }
  }

  private byte[] prepareResponse(byte[] bytes) {
    try {
      var root = JSON.readTree(bytes);
      if (!(root instanceof ObjectNode object)
          || !root.path("choices").isArray()
          || root.path("choices").isEmpty())
        throw ProviderErrorClassifier.error(
            ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
      var usage = root.get("usage");
      if (usage != null && !usage.isNull()) {
        if (!usage.isObject()) throw new IllegalArgumentException();
        capture.set(
            new Capture(
                new TokenUsage(
                    token(usage, "prompt_tokens"),
                    token(usage, "completion_tokens"),
                    token(usage, "total_tokens")),
                true));
        // Spring AI 将缺字段/超过 int 的用量视为异常；独立保留原生 nullable long，避免推断或补零。
        object.remove("usage");
        return JSON.writeValueAsBytes(object);
      }
      return bytes;
    } catch (ProviderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
    }
  }

  private static Long token(JsonNode usage, String key) {
    var value = usage.get(key);
    if (value == null || value.isNull()) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
      throw new IllegalArgumentException();
    return value.longValue();
  }

  public CompletableFuture<HttpResponse> executeAsync(HttpRequest r, RequestOptions o) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException("Only synchronous Provider calls are supported"));
  }

  public void close() {
    client.dispatcher().executorService().shutdownNow();
    client.connectionPool().evictAll();
  }

  private static final class BufferedResponse implements HttpResponse {
    private final int status;
    private final com.openai.core.http.Headers headers;
    private final ByteArrayInputStream input;

    BufferedResponse(int status, com.openai.core.http.Headers headers, byte[] body) {
      this.status = status;
      this.headers = headers;
      this.input = new ByteArrayInputStream(body);
    }

    public int statusCode() {
      return status;
    }

    public com.openai.core.http.Headers headers() {
      return headers;
    }

    public InputStream body() {
      return input;
    }

    public void close() {
      input.reset();
    }
  }
}

package com.aegisos.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.ContextSizer;
import com.aegisos.core.provider.ExternalOutcome;
import com.aegisos.core.tool.ToolResult;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/** 独立连接管理器与固定 DNS；总截止时间主动取消请求并关闭套接字，不只取消等待。 */
public final class ControlledHttpTransport {
  private static final ScheduledThreadPoolExecutor TIMER =
      new ScheduledThreadPoolExecutor(
          1,
          r -> {
            var t = new Thread(r, "aegisos-http-deadline");
            t.setDaemon(true);
            return t;
          });

  static {
    TIMER.setRemoveOnCancelPolicy(true);
  }

  public ToolResult get(HttpGetPermission permission) {
    permission.claim();
    var received = new AtomicBoolean();
    var expired = new AtomicBoolean();
    var request = new HttpGet(permission.uri);
    DnsResolver resolver =
        new DnsResolver() {
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!permission.uri.getHost().equals(host) || permission.remainingNanos() <= 0)
              throw new UnknownHostException("UNAPPROVED_HOST");
            return new InetAddress[] {permission.address};
          }

          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            resolve(host);
            return host;
          }
        };
    var limits = permission.policy;
    var connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setMaxConnTotal(1)
            .setMaxConnPerRoute(1)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(timeout(limits.connect().toNanos(), permission))
                    .setSocketTimeout(timeout(limits.read().toNanos(), permission))
                    .build())
            .build();
    try (var client =
        HttpClients.custom()
            .setConnectionManager(connections)
            .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableContentCompression()
            .disableAuthCaching()
            .disableConnectionState()
            .setDefaultAuthSchemeRegistry(
                org.apache.hc.core5.http.config.RegistryBuilder
                    .<org.apache.hc.client5.http.auth.AuthSchemeFactory>create()
                    .build())
            .setConnectionReuseStrategy((r, s, c) -> false)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setAuthenticationEnabled(false)
                    .setResponseTimeout(timeout(limits.read().toNanos(), permission))
                    .setConnectionRequestTimeout(timeout(limits.connect().toNanos(), permission))
                    .build())
            .build()) {
      long remaining = permission.remainingNanos();
      if (remaining <= 0) return failure(AgentErrorCode.TOOL_TIMEOUT, ExternalOutcome.NOT_SENT);
      var deadline =
          TIMER.schedule(
              () -> {
                expired.set(true);
                request.cancel();
                client.close(CloseMode.IMMEDIATE);
              },
              remaining,
              TimeUnit.NANOSECONDS);
      try {
        if (expired.get() || permission.remainingNanos() <= 0)
          return failure(AgentErrorCode.TOOL_TIMEOUT, ExternalOutcome.NOT_SENT);
        var response = client.executeOpen(null, request, null);
        try {
          received.set(true);
          if (response.getCode() < 200 || response.getCode() >= 300)
            return failure(AgentErrorCode.TOOL_EXECUTION_FAILED, ExternalOutcome.RESPONDED);
          var encoding = response.getFirstHeader("Content-Encoding");
          if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity"))
            return failure(AgentErrorCode.TOOL_EXECUTION_FAILED, ExternalOutcome.RESPONDED);
          var entity = response.getEntity();
          if (entity == null)
            return failure(AgentErrorCode.TOOL_EXECUTION_FAILED, ExternalOutcome.RESPONDED);
          var content = org.apache.hc.core5.http.ContentType.parse(entity.getContentType());
          String mime = content.getMimeType().toLowerCase(Locale.ROOT);
          if (!(mime.equals("application/json")
                  || mime.equals("text/plain")
                  || mime.equals("text/json"))
              || content.getCharset() != null
                  && !content.getCharset().equals(StandardCharsets.UTF_8))
            return failure(AgentErrorCode.TOOL_EXECUTION_FAILED, ExternalOutcome.RESPONDED);
          byte[] bytes;
          // 不关闭仍有剩余正文的流：某些实现会在 close 时 drain。统一先取消连接，后关闭响应。
          bytes = entity.getContent().readNBytes(limits.maxResponseBytes() + 1);
          if (bytes.length > limits.maxResponseBytes())
            return failure(AgentErrorCode.TOOL_RESPONSE_LIMIT, ExternalOutcome.RESPONDED);
          String body =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes))
                  .toString();
          if (expired.get() || permission.remainingNanos() <= 0)
            return failure(AgentErrorCode.TOOL_TIMEOUT, ExternalOutcome.RESPONDED);
          return new ToolResult(
              true,
              null,
              ExternalOutcome.RESPONDED,
              "{\"statusCode\":"
                  + response.getCode()
                  + ",\"mediaType\":"
                  + ContextSizer.quote(mime)
                  + ",\"body\":"
                  + ContextSizer.quote(body)
                  + "}");
        } finally {
          request.cancel();
          client.close(CloseMode.IMMEDIATE);
          try {
            response.close();
          } catch (java.io.IOException ignored) {
            /* 已主动关闭底层连接，不能覆盖已确定的响应状态。 */
          }
        }
      } finally {
        deadline.cancel(false);
        request.cancel();
        client.close(CloseMode.IMMEDIATE);
      }
    } catch (Exception e) {
      return failure(
          expired.get() || e instanceof java.net.SocketTimeoutException
              ? AgentErrorCode.TOOL_TIMEOUT
              : AgentErrorCode.TOOL_EXECUTION_FAILED,
          received.get() ? ExternalOutcome.RESPONDED : ExternalOutcome.UNKNOWN);
    } finally {
      connections.close(CloseMode.IMMEDIATE);
    }
  }

  private static Timeout timeout(long nanos, HttpGetPermission p) {
    return Timeout.ofMilliseconds(
        Math.max(
            1, TimeUnit.NANOSECONDS.toMillis(Math.min(nanos, Math.max(1, p.remainingNanos())))));
  }

  private static ToolResult failure(AgentErrorCode c, ExternalOutcome outcome) {
    return new ToolResult(false, c, outcome, null);
  }
}

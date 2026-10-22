package com.aegisos.web.baseline;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** 关联请求与日志；仅记录白名单元数据，不记录正文、URL、Header 或原始异常。 */
public final class RequestIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Request-ID";
  public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
  private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

  @Override
  @SuppressFBWarnings(
      value = "HRS_REQUEST_PARAMETER_TO_HTTP_HEADER",
      justification =
          "Full-match ASCII allowlist limits IDs to 64 safe characters; otherwise UUID.")
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String candidate = request.getHeader(HEADER);
    String id =
        candidate != null && VALID_ID.matcher(candidate).matches()
            ? candidate
            : UUID.randomUUID().toString();
    String previousId = MDC.get("requestId");
    long start = System.nanoTime();
    request.setAttribute(ATTRIBUTE, id);
    response.setHeader(HEADER, id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      LOG.atInfo()
          .addKeyValue("event", "http_request_completed")
          .addKeyValue("status", response.getStatus())
          .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
          .log("HTTP request completed");
      // 请求结束后恢复调用前的 MDC，避免线程复用或嵌套调用污染其他请求。
      if (previousId == null) {
        MDC.remove("requestId");
      } else {
        MDC.put("requestId", previousId);
      }
    }
  }
}

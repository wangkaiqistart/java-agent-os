package com.aegisos.web.baseline;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 在交给 MVC 前按字节限制 JSON 请求体；没有 Content-Length 的分块请求同样受限。 */
public final class JsonBodyLimitFilter extends OncePerRequestFilter {
  private final ObjectMapper mapper;
  private final int maxBytes;

  public JsonBodyLimitFilter(ObjectMapper mapper, int maxBytes) {
    if (maxBytes < 1 || maxBytes > 1024 * 1024) {
      throw new IllegalArgumentException("JSON body limit must be between 1 byte and 1 MiB");
    }
    // 保留 Boot 注册的 ProblemDetail 等映射模块，避免共享可关闭的 mapper 实例。
    this.mapper = mapper.rebuild().build();
    this.maxBytes = maxBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String contentType = request.getContentType();
    if (contentType == null) {
      chain.doFilter(request, response);
      return;
    }
    String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (!type.equals(MediaType.APPLICATION_JSON_VALUE) && !type.endsWith("+json")) {
      chain.doFilter(request, response);
      return;
    }
    // 最多多读一个字节以判定越界，不按不可信的 Content-Length 分配内存。
    byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
    if (body.length > maxBytes) {
      response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(), ApiProblems.create(HttpStatus.PAYLOAD_TOO_LARGE, request));
      return;
    }
    chain.doFilter(new BufferedJsonRequest(request, body), response);
  }

  private static final class BufferedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedJsonRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
          return input.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException(
              "JSON replay supports synchronous MVC reads only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}

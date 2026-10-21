package com.aegisos.web.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Bounds JSON bodies even when a client streams without Content-Length. */
public final class JsonBodyLimitFilter extends OncePerRequestFilter {
  private final ObjectMapper mapper;
  private final int maxBytes;

  public JsonBodyLimitFilter(ObjectMapper mapper, int maxBytes) {
    if (maxBytes < 1 || maxBytes > 1024 * 1024) {
      throw new IllegalArgumentException("JSON body limit must be between 1 byte and 1 MiB");
    }
    this.mapper = mapper.copy();
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

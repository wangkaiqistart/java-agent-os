package com.aegisos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aegisos.web.baseline.RequestIdFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  @Test
  void replacesHeaderInjectionAndControlCharacters() throws Exception {
    for (String unsafe : java.util.List.of("id\r\nInjected: value", "id\n", "id\t", "id/other")) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("X-Request-ID", unsafe);
      MockHttpServletResponse response = new MockHttpServletResponse();
      new RequestIdFilter().doFilter(request, response, (req, res) -> {});
      assertThat(java.util.UUID.fromString(response.getHeader("X-Request-ID"))).isNotNull();
    }
  }

  @Test
  void restoresPreviousContextWhenTheDownstreamFails() {
    MDC.put("requestId", "previous-context");
    try {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("X-Request-ID", "new-context");
      assertThatThrownBy(
              () ->
                  new RequestIdFilter()
                      .doFilter(
                          request,
                          new MockHttpServletResponse(),
                          (req, res) -> {
                            assertThat(MDC.get("requestId")).isEqualTo("new-context");
                            throw new ServletException("failure");
                          }))
          .isInstanceOf(ServletException.class);
      assertThat(MDC.get("requestId")).isEqualTo("previous-context");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void removesNewContextWhenNoPreviousContextExists() throws Exception {
    MDC.clear();
    new RequestIdFilter()
        .doFilter(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            (req, res) -> assertThat(MDC.get("requestId")).isNotBlank());
    assertThat(MDC.get("requestId")).isNull();
  }
}

package com.aegisos.web.baseline;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** 构造安全且稳定的错误响应；禁止传入上游异常消息，防止正文、SQL 或凭据泄露。 */
final class ApiProblems {
  private ApiProblems() {}

  static ProblemDetail create(HttpStatus status, HttpServletRequest request) {
    String code =
        switch (status) {
          case BAD_REQUEST -> "INVALID_REQUEST";
          case NOT_FOUND -> "NOT_FOUND";
          case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
          case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
          case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
          default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
    problem.setType(URI.create("urn:aegisos:error:" + code.toLowerCase(java.util.Locale.ROOT)));
    problem.setInstance(URI.create("/"));
    problem.setProperty("code", code);
    problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
    return problem;
  }
}

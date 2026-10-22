package com.aegisos.web.baseline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 保留 MVC 的 HTTP 状态和协议 Header，用统一安全描述替换异常与绑定细节。 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
    HttpStatus safeStatus = HttpStatus.resolve(status.value());
    if (safeStatus == null) {
      safeStatus = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    return new ResponseEntity<>(
        ApiProblems.create(safeStatus, servletRequest), headers, safeStatus);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
    return ResponseEntity.internalServerError()
        .body(ApiProblems.create(HttpStatus.INTERNAL_SERVER_ERROR, request));
  }
}

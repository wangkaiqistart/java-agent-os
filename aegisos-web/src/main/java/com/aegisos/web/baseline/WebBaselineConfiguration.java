package com.aegisos.web.baseline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/** 显式装配兄弟模块的 Web 基础设施；请求关联必须先于大小限制，以保证 413 也有请求 ID。 */
@Configuration(proxyBeanMethods = false)
@Import(ApiExceptionHandler.class)
public class WebBaselineConfiguration {
  @Bean
  FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Bean
  FilterRegistrationBean<JsonBodyLimitFilter> jsonBodyLimitFilter(
      ObjectMapper mapper, @Value("${aegisos.http.max-json-body-bytes:32768}") int maxBytes) {
    FilterRegistrationBean<JsonBodyLimitFilter> registration =
        new FilterRegistrationBean<>(new JsonBodyLimitFilter(mapper, maxBytes));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return registration;
  }
}

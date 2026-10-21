package com.aegisos.web.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

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

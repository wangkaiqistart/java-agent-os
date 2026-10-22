package com.aegisos.provider.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** 不包含凭证值；拒绝配置错误时不得打印原始字段。 */
public record ProviderDefinition(
    String name,
    String adapter,
    Set<String> models,
    URI baseUrl,
    String credentialRef,
    int maxRetries,
    Duration connectTimeout,
    Duration readTimeout) {
  public ProviderDefinition {
    models = models == null ? Set.of() : Set.copyOf(models);
  }

  @Override
  public String toString() {
    return "ProviderDefinition[redacted]";
  }
}

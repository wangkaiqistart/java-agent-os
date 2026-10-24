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
    Duration readTimeout,
    ProviderThinkingMode thinkingMode) {
  /** 保留既有调用入口；缺省不添加任何厂商thinking扩展，不能通过名称推断。 */
  public ProviderDefinition(
      String name,
      String adapter,
      Set<String> models,
      URI baseUrl,
      String credentialRef,
      int maxRetries,
      Duration connectTimeout,
      Duration readTimeout) {
    this(
        name,
        adapter,
        models,
        baseUrl,
        credentialRef,
        maxRetries,
        connectTimeout,
        readTimeout,
        ProviderThinkingMode.UNSPECIFIED);
  }

  public ProviderDefinition {
    models = models == null ? Set.of() : Set.copyOf(models);
  }

  @Override
  public String toString() {
    return "ProviderDefinition[redacted]";
  }
}

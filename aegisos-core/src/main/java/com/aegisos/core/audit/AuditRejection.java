package com.aegisos.core.audit;

import com.aegisos.core.provider.ProviderErrorCode;
import java.time.Instant;
import java.util.UUID;

/** 关联在开始/拒绝时固定；兼容构造器保持旧入口的空关联。 */
public record AuditRejection(
    UUID requestId,
    String providerName,
    String modelName,
    Instant createdAt,
    ProviderErrorCode errorCode,
    String sessionId) {
  public AuditRejection(
      UUID requestId,
      String providerName,
      String modelName,
      Instant createdAt,
      ProviderErrorCode errorCode) {
    this(requestId, providerName, modelName, createdAt, errorCode, null);
  }

  @Override
  public String toString() {
    return "AuditRejection[redacted]";
  }
}

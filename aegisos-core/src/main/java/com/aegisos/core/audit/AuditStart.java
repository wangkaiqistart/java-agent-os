package com.aegisos.core.audit;

import java.time.Instant;
import java.util.UUID;

/** 关联在开始/拒绝时固定；兼容构造器保持旧入口的空关联。 */
public record AuditStart(
    UUID requestId,
    long attemptNo,
    String providerName,
    String modelName,
    Instant createdAt,
    String sessionId,
    com.aegisos.core.provider.ProviderInvocation invocation) {
  public AuditStart(
      UUID requestId,
      long attemptNo,
      String providerName,
      String modelName,
      Instant createdAt,
      String sessionId) {
    this(requestId, attemptNo, providerName, modelName, createdAt, sessionId, null);
  }

  public AuditStart(
      UUID requestId, long attemptNo, String providerName, String modelName, Instant createdAt) {
    this(requestId, attemptNo, providerName, modelName, createdAt, null);
  }

  @Override
  public String toString() {
    return "AuditStart[redacted]";
  }
}

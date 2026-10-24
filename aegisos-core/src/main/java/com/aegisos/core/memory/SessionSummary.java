package com.aegisos.core.memory;

import java.time.Instant;
import java.util.UUID;

/** 安全摘要仅包含身份和计数；数据库中的不可信字段同样必须验证。 */
public record SessionSummary(
    String sessionId,
    String profileName,
    Instant createdAt,
    Instant updatedAt,
    long messageCount,
    long processingCount) {
  public SessionSummary {
    try {
      if (!UUID.fromString(sessionId).toString().equals(sessionId)
          || !profileName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
          || createdAt == null
          || updatedAt == null
          || messageCount < 0
          || processingCount < 0) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("INVALID_SESSION_SUMMARY");
    }
  }

  @Override
  public String toString() {
    return "SessionSummary[redacted]";
  }
}

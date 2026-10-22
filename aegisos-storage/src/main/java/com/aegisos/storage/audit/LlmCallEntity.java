package com.aegisos.storage.audit;

import com.aegisos.core.audit.*;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "llm_calls")
public class LlmCallEntity {
  @Id private String id;

  @Column(name = "request_id", nullable = false)
  private String requestId;

  @Column(name = "attempt_no")
  private long attemptNo;

  @Column(name = "provider_name")
  private String providerName;

  @Column(name = "model_name")
  private String modelName;

  @Column(name = "session_id")
  private String sessionId;

  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  private String status;

  @Column(name = "created_at")
  private String createdAt;

  @Column(name = "completed_at")
  private String completedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "input_tokens")
  private Long inputTokens;

  @Column(name = "output_tokens")
  private Long outputTokens;

  @Column(name = "total_tokens")
  private Long totalTokens;

  @Column(name = "error_code")
  private String errorCode;

  private boolean retryable;

  protected LlmCallEntity() {}

  static LlmCallEntity started(UUID id, AuditStart s) {
    var e = new LlmCallEntity();
    e.id = id.toString();
    e.requestId = s.requestId().toString();
    e.attemptNo = s.attemptNo();
    e.providerName = s.providerName();
    e.modelName = s.modelName();
    e.sessionId = s.sessionId();
    e.status = "STARTED";
    e.createdAt = s.createdAt().toString();
    return e;
  }

  static LlmCallEntity rejected(AuditRejection r) {
    var e =
        started(
            UUID.randomUUID(),
            new AuditStart(
                r.requestId(), 0, r.providerName(), r.modelName(), r.createdAt(), r.sessionId()));
    e.status = "REJECTED";
    e.success = false;
    e.errorMessage = SafeAuditErrorMessages.forCode(r.errorCode());
    e.completedAt = e.createdAt;
    e.durationMs = 0L;
    e.errorCode = r.errorCode().name();
    return e;
  }

  /** 课程兼容的三态结果：未结束为null，成功为true，失败/拒绝为false；由JPA字段映射存取。 */
  public Boolean getSuccess() {
    return success;
  }

  public String getStatus() {
    return status;
  }

  public boolean isRetryable() {
    return retryable;
  }
}

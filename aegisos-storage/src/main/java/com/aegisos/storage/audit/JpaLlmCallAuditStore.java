package com.aegisos.storage.audit;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import jakarta.persistence.*;
import java.util.UUID;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式短事务：提交异常也转换为安全错误，不把 save/flush 当成提交。 */
public final class JpaLlmCallAuditStore implements LlmCallAuditStore {
  private final LlmCallRepository repository;
  private final TransactionTemplate tx;

  public JpaLlmCallAuditStore(EntityManagerFactory emf, PlatformTransactionManager tm) {
    this.repository = new LlmCallRepository(emf);
    this.tx = new TransactionTemplate(tm);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 二次校验并冻结安全身份与会话；REQUIRES_NEW完成提交后才返回句柄。 任意持久化/提交异常只转换AUDIT_UNAVAILABLE，原始SQL及异常不传播。 */
  public AuditHandle begin(AuditStart s) {
    try {
      if (s == null) throw new AuditUnavailableException();
      require(s.requestId() != null && s.attemptNo() > 0 && s.createdAt() != null);
      var safe =
          new AuditStart(
              s.requestId(),
              s.attemptNo(),
              SafeProviderMetadata.provider(s.providerName()),
              SafeProviderMetadata.model(s.modelName()),
              s.createdAt(),
              SessionAssociation.safe(s.sessionId()));
      var id = UUID.randomUUID();
      tx.executeWithoutResult(status -> repository.insert(LlmCallEntity.started(id, safe)));
      return new AuditHandle(id);
    } catch (RuntimeException e) {
      throw new AuditUnavailableException();
    }
  }

  /** 只允许STARTED到终态的单条条件更新，命中必须为1；不可改会话，不自动修复或重放。 */
  public void complete(AuditHandle h, AuditCompletion c) {
    try {
      if (h == null || c == null) throw new AuditUnavailableException();
      require(h.id() != null && c.completedAt() != null && c.durationMs() >= 0);
      require(
          (c.status() == AuditStatus.SUCCEEDED && c.errorCode() == null && !c.retryable())
              || (c.status() == AuditStatus.FAILED && c.errorCode() != null));
      tx.executeWithoutResult(
          status -> {
            int count = repository.complete(h, c);
            require(count == 1);
          });
    } catch (RuntimeException e) {
      throw new AuditUnavailableException();
    }
  }

  /** 本地拒绝直接独立提交终态，attemptNo=0；错误说明仅由受控枚举派生。 */
  public void reject(AuditRejection r) {
    try {
      if (r == null) throw new AuditUnavailableException();
      require(r.requestId() != null && r.createdAt() != null && r.errorCode() != null);
      var safe =
          new AuditRejection(
              r.requestId(),
              SafeProviderMetadata.provider(r.providerName()),
              SafeProviderMetadata.model(r.modelName()),
              r.createdAt(),
              r.errorCode(),
              SessionAssociation.safe(r.sessionId()));
      tx.executeWithoutResult(status -> repository.insert(LlmCallEntity.rejected(safe)));
    } catch (RuntimeException e) {
      throw new AuditUnavailableException();
    }
  }

  private static void require(boolean valid) {
    if (!valid) throw new AuditUnavailableException();
  }
}

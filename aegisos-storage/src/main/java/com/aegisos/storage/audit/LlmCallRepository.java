package com.aegisos.storage.audit;

import com.aegisos.core.audit.*;
import jakarta.persistence.*;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

/** 显式 JPA 仓库，只在调用方已建立的短事务内工作；不使用 save/merge。 */
final class LlmCallRepository {
  private final EntityManagerFactory emf;

  LlmCallRepository(EntityManagerFactory emf) {
    this.emf = emf;
  }

  private EntityManager em() {
    return EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
  }

  void insert(LlmCallEntity entity) {
    em().persist(entity);
  }

  int complete(AuditHandle h, AuditCompletion c) {
    var u = c.usage();
    int count =
        em().createQuery(
                "UPDATE LlmCallEntity e SET e.status=:status,e.success=:success,e.errorMessage=:message,e.completedAt=:time,e.durationMs=:duration,e.inputTokens=:input,e.outputTokens=:output,e.totalTokens=:total,e.errorCode=:error,e.retryable=:retry WHERE e.id=:id AND e.status='STARTED'")
            .setParameter("status", c.status().name())
            .setParameter("success", c.status() == AuditStatus.SUCCEEDED)
            .setParameter("message", SafeAuditErrorMessages.forCode(c.errorCode()))
            .setParameter("time", c.completedAt().toString())
            .setParameter("duration", c.durationMs())
            .setParameter("input", u == null ? null : u.inputTokens())
            .setParameter("output", u == null ? null : u.outputTokens())
            .setParameter("total", u == null ? null : u.totalTokens())
            .setParameter("error", c.errorCode() == null ? null : c.errorCode().name())
            .setParameter("retry", c.retryable())
            .setParameter("id", h.id().toString())
            .executeUpdate();
    return count;
  }
}

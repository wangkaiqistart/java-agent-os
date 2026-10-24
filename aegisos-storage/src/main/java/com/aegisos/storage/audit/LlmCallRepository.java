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

  /** begin/reject 的同一事务内校验 PREPARED/RUNNING/session；先验证后链接，失败不留下半条记录。 */
  void link(
      java.util.UUID requestId,
      String session,
      com.aegisos.core.provider.ProviderInvocation invocation) {
    if (invocation == null) return;
    if (invocation.processId() == null || invocation.iterationNo() < 1 || session == null)
      throw new InvalidProviderInvocationException();
    var valid =
        em().createNativeQuery(
                "SELECT p.id FROM agent_processings p JOIN model_turns t ON t.process_id=p.id WHERE p.id=?1 AND p.session_id=?2 AND p.status='RUNNING' AND t.iteration_no=?3 AND t.status='PREPARED'")
            .setParameter(1, invocation.processId().toString())
            .setParameter(2, session)
            .setParameter(3, invocation.iterationNo())
            .getResultList();
    if (valid.size() != 1) throw new InvalidProviderInvocationException();
    var old =
        em().createNativeQuery(
                "SELECT request_id FROM llm_request_links WHERE process_id=?1 AND iteration_no=?2")
            .setParameter(1, invocation.processId().toString())
            .setParameter(2, invocation.iterationNo())
            .getResultList();
    if (!old.isEmpty()) {
      if (old.size() != 1 || !requestId.toString().equals(old.getFirst().toString()))
        throw new InvalidProviderInvocationException();
      return; // 同一逻辑请求的 retry 保持 requestId，不重绑轮次。
    }
    int count =
        em().createNativeQuery(
                "INSERT INTO llm_request_links(request_id,process_id,iteration_no) VALUES(?1,?2,?3)")
            .setParameter(1, requestId.toString())
            .setParameter(2, invocation.processId().toString())
            .setParameter(3, invocation.iterationNo())
            .executeUpdate();
    if (count != 1) throw new AuditUnavailableException();
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

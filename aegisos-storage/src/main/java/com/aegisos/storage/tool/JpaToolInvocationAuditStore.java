package com.aegisos.storage.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.audit.ToolInvocationAuditStore;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 工具审计只保存白名单元数据；每个方法返回时独立事务已提交。 */
public final class JpaToolInvocationAuditStore implements ToolInvocationAuditStore {
  // 仅持有“取得当前短事务EntityManager”的能力，不向仓库暴露工厂生命周期/可变配置。
  private final java.util.function.Supplier<EntityManager> currentEntityManager;
  private final TransactionTemplate tx;

  public JpaToolInvocationAuditStore(EntityManagerFactory emf, PlatformTransactionManager manager) {
    Objects.requireNonNull(emf);
    currentEntityManager =
        () ->
            Objects.requireNonNull(
                EntityManagerFactoryUtils.getTransactionalEntityManager(emf),
                "TRANSACTION_REQUIRED");
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private <T> T transaction(Function<EntityManager, T> action) {
    try {
      return tx.execute(s -> action.apply(currentEntityManager.get()));
    } catch (RuntimeException e) {
      throw failure();
    }
  }

  private static AgentException failure() {
    return new AgentException(AgentErrorCode.AUDIT_UNAVAILABLE);
  }

  private static void require(boolean condition) {
    if (!condition) throw failure();
  }

  private static Query q(EntityManager em, String sql, Object... args) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return q;
  }

  private void insert(Start s, AgentErrorCode code) {
    require(
        s != null
            && s.id() != null
            && s.handle() != null
            && s.startedAt() != null
            && s.iterationNo() > 0
            && s.callOrdinal() > 0
            && s.rawCallId() != null
            && s.rawCallId().matches("[A-Za-z0-9_.-]{1,128}")
            && s.toolName() != null
            && s.toolName().matches("[A-Za-z0-9_-]{1,64}")
            && s.fingerprint() != null
            && s.fingerprint().matches("[0-9a-f]{64}"));
    transaction(
        em -> {
          var h = s.handle();
          var count =
              (Number)
                  q(
                          em,
                          "SELECT count(*) FROM agent_processings p JOIN sessions s ON s.id=p.session_id JOIN model_turns t ON t.process_id=p.id WHERE p.id=?1 AND p.session_id=?2 AND s.profile_name=?3 AND p.runtime_instance_id=?4 AND p.version=?5 AND p.status='RUNNING' AND t.iteration_no=?6 AND t.status='ACCEPTED'",
                          h.processId().toString(),
                          h.sessionId(),
                          h.profileName(),
                          h.runtimeInstanceId().toString(),
                          h.version(),
                          s.iterationNo())
                      .getSingleResult();
          require(count.intValue() == 1);
          require(
              q(
                          em,
                          "INSERT INTO tool_invocations(id,session_id,process_id,raw_call_id,tool_name,first_iteration_no,call_ordinal,arguments_fingerprint,status,started_at,completed_at,duration_ms,success,error_code,error_message,external_outcome) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,'NOT_SENT')",
                          s.id().toString(),
                          h.sessionId(),
                          h.processId().toString(),
                          s.rawCallId(),
                          s.toolName(),
                          s.iterationNo(),
                          s.callOrdinal(),
                          s.fingerprint(),
                          code == null ? "STARTED" : "REJECTED",
                          s.startedAt().toString(),
                          code == null ? null : s.startedAt().toString(),
                          code == null ? null : 0,
                          code == null ? null : 0,
                          code == null ? null : code.name(),
                          code == null ? null : code.name())
                      .executeUpdate()
                  == 1);
          return null;
        });
  }

  public void begin(Start s) {
    insert(s, null);
  }

  public void reject(Start s, AgentErrorCode code) {
    require(code != null);
    insert(s, code);
  }

  public void complete(UUID id, ToolResult r, Instant at, long ms) {
    require(
        id != null
            && r != null
            && at != null
            && ms >= 0
            && r.externalOutcome() != null
            && (r.success()
                ? r.errorCode() == null && r.externalOutcome() == ExternalOutcome.RESPONDED
                : r.errorCode() != null));
    transaction(
        em -> {
          require(
              q(
                          em,
                          "UPDATE tool_invocations SET status=?1,completed_at=?2,duration_ms=?3,success=?4,error_code=?5,error_message=?5,external_outcome=?6 WHERE id=?7 AND status='STARTED'",
                          r.success() ? "SUCCEEDED" : "FAILED",
                          at.toString(),
                          ms,
                          r.success() ? 1 : 0,
                          r.errorCode() == null ? null : r.errorCode().name(),
                          r.externalOutcome().name(),
                          id.toString())
                      .executeUpdate()
                  == 1);
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  public Optional<Record> find(UUID process, String call) {
    require(process != null && call != null && call.matches("[A-Za-z0-9_.-]{1,128}"));
    return transaction(
        em -> {
          List<Object[]> rows =
              q(
                      em,
                      "SELECT id,tool_name,arguments_fingerprint,status FROM tool_invocations WHERE process_id=?1 AND raw_call_id=?2",
                      process.toString(),
                      call)
                  .getResultList();
          if (rows.isEmpty()) return Optional.empty();
          var r = rows.getFirst();
          return Optional.of(
              new Record(
                  UUID.fromString(r[0].toString()),
                  r[1].toString(),
                  r[2].toString(),
                  r[3].toString()));
        });
  }
}

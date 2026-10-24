package com.aegisos.storage.runtime;

import com.aegisos.core.agent.*;
import jakarta.persistence.*;
import java.time.Clock;
import java.util.*;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 运行锁与V3校验之后的单事务恢复；绝不重发外部动作或猜测STARTED审计的最终结果。 */
public final class RuntimeRecoveryService {
  public RuntimeRecoveryService(
      EntityManagerFactory emf, PlatformTransactionManager manager, Clock clock) {
    var tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    try {
      tx.executeWithoutResult(
          status -> {
            var em = EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
            if (em == null) throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
            String at = clock.instant().toString();
            var ids =
                em.createNativeQuery("SELECT id FROM agent_processings WHERE status='RUNNING'")
                    .getResultList();
            for (Object id : ids) {
              int count =
                  em.createNativeQuery(
                          "UPDATE agent_processings SET status='INTERRUPTED',completed_at=?1,error_code='RECOVERED_INTERRUPTION',version=version+1 WHERE id=?2 AND status='RUNNING'")
                      .setParameter(1, at)
                      .setParameter(2, id)
                      .executeUpdate();
              if (count != 1) throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
              em.createNativeQuery(
                      "UPDATE model_turns SET status='INTERRUPTED',completed_at=?1,error_code='RECOVERED_INTERRUPTION' WHERE process_id=?2 AND status='PREPARED'")
                  .setParameter(1, at)
                  .setParameter(2, id)
                  .executeUpdate();
              em.createNativeQuery(
                      "INSERT INTO processing_events(id,process_id,event_type,created_at,reason_code,count) VALUES(?1,?2,'RECOVERED',?3,'RECOVERED_INTERRUPTION',1)")
                  .setParameter(1, UUID.randomUUID().toString())
                  .setParameter(2, id)
                  .setParameter(3, at)
                  .executeUpdate();
            }
          });
    } catch (RuntimeException e) {
      throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
    }
  }
}

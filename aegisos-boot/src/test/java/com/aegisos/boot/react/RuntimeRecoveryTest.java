package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.audit.*;
import com.aegisos.core.provider.ProviderInvocation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuntimeRecoveryTest {
  @Test
  void restartMarksRunningAndPreparedInterruptedWithoutTouchingStartedAuditOrSending()
      throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      UUID process;
      String session;
      try (var r = f.openRuntime()) {
        session = r.agents().createSession("p").sessionId();
        var h = r.memory().beginProcessing(session, "p", "safe", UUID.randomUUID());
        h = r.memory().prepareModelTurn(h, 1);
        process = h.processId();
        r.context()
            .getBean(LlmCallAuditStore.class)
            .begin(
                new AuditStart(
                    UUID.randomUUID(),
                    1,
                    "model-provider",
                    "test-model",
                    Instant.now(),
                    session,
                    new ProviderInvocation(process, 1)));
      }
      try (var r = f.openRuntime();
          var c = f.reopen();
          var s = c.createStatement()) {
        try (var rows = s.executeQuery("select status,error_code from agent_processings")) {
          assertThat(rows.getString(1)).isEqualTo("INTERRUPTED");
          assertThat(rows.getString(2)).isEqualTo("RECOVERED_INTERRUPTION");
        }
        try (var rows = s.executeQuery("select status from model_turns")) {
          assertThat(rows.getString(1)).isEqualTo("INTERRUPTED");
        }
        try (var rows = s.executeQuery("select status from llm_calls")) {
          assertThat(rows.getString(1)).isEqualTo("STARTED");
        }
        try (var rows = s.executeQuery("select event_type,reason_code from processing_events")) {
          assertThat(rows.getString(1)).isEqualTo("RECOVERED");
          assertThat(rows.getString(2)).isEqualTo("RECOVERED_INTERRUPTION");
        }
        assertThat(r.memory().loadSession(session, "p").messages()).hasSize(1);
        assertThat(f.httpRequests).hasValue(0);
      }
    }
  }

  @Test
  void recoveryCommitFailurePreventsOpeningAgentRuntime() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      try (var r = f.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        r.memory().beginProcessing(session, "p", "safe", UUID.randomUUID());
      }
      try (var c = f.reopen();
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER fail_recovery BEFORE UPDATE ON agent_processings BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
      }
      assertThatThrownBy(() -> f.openRuntime()).isInstanceOf(RuntimeException.class);
      assertThat(f.httpRequests).hasValue(0);
    }
  }

  @Test
  void rejectedRecoveryCommitRollsBackPreparedTurnsAndEvents() throws Exception {
    try (var f = new ReactRuntimeFixture();
        var runtime = f.openRuntime()) {
      String session = runtime.agents().createSession("p").sessionId();
      var h = runtime.memory().beginProcessing(session, "p", "safe", UUID.randomUUID());
      runtime.memory().prepareModelTurn(h, 1);
      var real =
          runtime
              .context()
              .getBean(org.springframework.transaction.PlatformTransactionManager.class);
      var rejecting =
          new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
              return real.getTransaction(definition);
            }

            public void rollback(org.springframework.transaction.TransactionStatus status) {
              real.rollback(status);
            }

            public void commit(org.springframework.transaction.TransactionStatus status) {
              real.rollback(status);
              throw new IllegalStateException("FIXTURE_COMMIT_REJECTED");
            }
          };
      assertThatThrownBy(
              () ->
                  new com.aegisos.storage.runtime.RuntimeRecoveryService(
                      runtime.context().getBean(jakarta.persistence.EntityManagerFactory.class),
                      rejecting,
                      java.time.Clock.systemUTC()))
          .hasMessage("PERSISTENCE_UNCONFIRMED");
      try (var connection = f.reopen();
          var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("SELECT status FROM agent_processings")) {
          assertThat(rows.getString(1)).isEqualTo("RUNNING");
        }
        try (var rows = statement.executeQuery("SELECT status FROM model_turns")) {
          assertThat(rows.getString(1)).isEqualTo("PREPARED");
        }
        try (var rows = statement.executeQuery("SELECT count(*) FROM processing_events")) {
          assertThat(rows.getInt(1)).isZero();
        }
      }
      assertThat(f.physicalSends).hasValue(0);
    }
  }
}

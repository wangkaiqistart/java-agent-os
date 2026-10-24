package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.audit.*;
import com.aegisos.core.provider.ProviderInvocation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PipelineCrashIntegrationTest {
  @Test
  void restartNeverReplaysModelOrToolAtFourPersistedCrashBoundaries() throws Exception {
    for (int stage = 0; stage < 4; stage++)
      try (var f = new ToolLoopFixture()) {
        f.responses.set(n -> f.response(f.tool("id", "/weather")));
        String session;
        String auditStatus;
        try (var r = f.base.openRuntime()) {
          session = r.agents().createSession("p").sessionId();
          if (stage == 0) {
            var h = r.memory().beginProcessing(session, "p", "safe", UUID.randomUUID());
            h = r.memory().prepareModelTurn(h, 1);
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
                        new ProviderInvocation(h.processId(), 1)));
          } else {
            try (var c = f.base.reopen();
                var s = c.createStatement()) {
              s.execute(
                  "CREATE TRIGGER block_finish BEFORE UPDATE OF status ON agent_processings BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
              String clause =
                  stage == 1
                      ? "BEFORE INSERT ON tool_invocations"
                      : stage == 2
                          ? "BEFORE UPDATE ON tool_invocations"
                          : "BEFORE INSERT ON session_messages WHEN NEW.role='TOOL'";
              s.execute(
                  "CREATE TRIGGER block_boundary "
                      + clause
                      + " BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
            }
            var result = r.agents().process(new AgentRequest(session, "p", "safe"));
            assertThat(result.persistenceConfirmed()).isFalse();
            try (var c = f.base.reopen();
                var s = c.createStatement()) {
              s.execute("DROP TRIGGER block_finish");
              s.execute("DROP TRIGGER block_boundary");
            }
          }
          auditStatus = stage == 0 ? "STARTED" : "SUCCEEDED";
        }
        int sent = f.base.httpRequests.get(), gets = f.gets.get();
        try (var r = f.base.openRuntime();
            var c = f.base.reopen();
            var s = c.createStatement()) {
          assertThat(f.base.httpRequests).hasValue(sent);
          assertThat(f.gets).hasValue(gets);
          try (var rows = s.executeQuery("select status from agent_processings")) {
            assertThat(rows.getString(1)).isEqualTo("INTERRUPTED");
          }
          if (stage == 0 || f.base.secureReadsSupported())
            try (var rows = s.executeQuery("select status from llm_calls")) {
              assertThat(rows.getString(1)).isEqualTo(auditStatus);
            }
          if (f.base.secureReadsSupported() && stage >= 2)
            try (var rows = s.executeQuery("select status from tool_invocations")) {
              assertThat(rows.getString(1)).isEqualTo(stage == 2 ? "STARTED" : "SUCCEEDED");
            }
          assertThat(r.memory().loadSession(session, "p").messages()).isNotEmpty();
        }
      }
  }
}

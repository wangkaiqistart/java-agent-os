package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class ToolFailureBoundaryTest {
  @Test
  void auditAndObservationFailuresStopWithoutReplayingEffects() throws Exception {
    for (String clause :
        new String[] {
          "BEFORE INSERT ON tool_invocations",
          "BEFORE UPDATE ON tool_invocations",
          "BEFORE INSERT ON session_messages WHEN NEW.role='TOOL'"
        }) {
      try (var f = new ToolLoopFixture()) {
        f.responses.set(n -> f.response(f.tool("one", "/weather")));
        try (var r = f.base.openRuntime()) {
          try (var c = f.base.reopen();
              var s = c.createStatement()) {
            s.execute(
                "CREATE TRIGGER fail_tool "
                    + clause
                    + " BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
          }
          String session = r.agents().createSession("p").sessionId();
          var result = r.agents().process(new AgentRequest(session, "p", "safe"));
          assertThat(result.status()).isEqualTo(ProcessingStatus.FAILED);
          assertThat(result.reply()).isNull();
          if (f.base.secureReadsSupported()) {
            assertThat(f.base.httpRequests).hasValue(1);
            assertThat(f.gets).hasValue(clause.equals("BEFORE INSERT ON tool_invocations") ? 0 : 1);
            assertThat(result.errorCode())
                .isEqualTo(
                    clause.contains("session_messages")
                        ? AgentErrorCode.PERSISTENCE_UNCONFIRMED
                        : AgentErrorCode.AUDIT_UNAVAILABLE);
          } else {
            assertThat(f.gets).hasValue(0);
            assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          }
        }
      }
    }
  }

  @Test
  void duplicateResponseIdsRejectEntireBatchAndSensitiveGetKeepsRespondedFact() throws Exception {
    for (boolean sensitive : new boolean[] {false, true})
      try (var f = new ToolLoopFixture()) {
        f.responses.set(
            n ->
                sensitive
                    ? f.response(f.tool("one", "/weather"))
                    : f.response(f.tool("one", "/weather"), f.tool("one", "/other")));
        if (sensitive) f.body.set("synthetic-runtime-credential-123");
        try (var r = f.base.openRuntime()) {
          String session = r.agents().createSession("p").sessionId();
          var result = r.agents().process(new AgentRequest(session, "p", "safe"));
          if (f.base.secureReadsSupported()) {
            assertThat(result.errorCode())
                .isEqualTo(
                    sensitive
                        ? AgentErrorCode.SENSITIVE_CONTENT
                        : AgentErrorCode.TOOL_CALL_CONFLICT);
            assertThat(f.gets).hasValue(sensitive ? 1 : 0);
            assertThat(f.base.httpRequests).hasValue(1);
            if (sensitive)
              try (var c = f.base.reopen();
                  var s = c.createStatement();
                  var rows =
                      s.executeQuery(
                          "select status,error_code,external_outcome from tool_invocations")) {
                assertThat(rows.getString(1)).isEqualTo("FAILED");
                assertThat(rows.getString(2)).isEqualTo("SENSITIVE_CONTENT");
                assertThat(rows.getString(3)).isEqualTo("RESPONDED");
              }
            assertThat(r.memory().loadSession(session, "p").messages())
                .allSatisfy(
                    m ->
                        assertThat(m.content()).doesNotContain("synthetic-runtime-credential-123"));
          } else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        }
      }
  }
}

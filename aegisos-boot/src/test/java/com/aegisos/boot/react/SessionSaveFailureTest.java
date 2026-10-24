package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class SessionSaveFailureTest {
  private void trigger(ReactRuntimeFixture fixture, String clause) throws Exception {
    try (var c = fixture.reopen();
        var s = c.createStatement()) {
      s.execute(
          "CREATE TRIGGER fail_save "
              + clause
              + " BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
    }
  }

  @Test
  void beginFailureRollsBackUserAndHasNoModelSend() throws Exception {
    try (var f = new ReactRuntimeFixture();
        var r = f.openRuntime()) {
      String session = r.agents().createSession("p").sessionId();
      trigger(f, "BEFORE INSERT ON session_messages");
      var result = r.agents().process(new AgentRequest(session, "p", "safe"));
      assertThat(result.errorCode()).isEqualTo(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
      assertThat(result.persistenceConfirmed()).isFalse();
      assertThat(result.reply()).isNull();
      assertThat(f.httpRequests).hasValue(0);
      assertThat(r.memory().loadSession(session, "p").messages()).isEmpty();
      assertThat(ProfileContext.current()).isNull();
    }
  }

  @Test
  void responseOrTerminalCommitFailureNeverClaimsSuccessAndKeepsRealAudit() throws Exception {
    for (boolean terminal : new boolean[] {false, true}) {
      try (var f = new ReactRuntimeFixture();
          var r = f.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        trigger(
            f,
            terminal
                ? "BEFORE UPDATE OF status ON agent_processings WHEN NEW.status='COMPLETED'"
                : "BEFORE INSERT ON session_messages WHEN NEW.role='ASSISTANT'");
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        assertThat(result.status()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(result.reply()).isNull();
        if (f.secureReadsSupported()) {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
          assertThat(f.httpRequests).hasValue(1);
          try (var c = f.reopen();
              var s = c.createStatement();
              var rows =
                  s.executeQuery("select count(*) from llm_calls where status='SUCCEEDED'")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
          }
        } else {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          assertThat(f.httpRequests).hasValue(0);
        }
        assertThat(r.memory().loadSession(session, "p").messages())
            .extracting(m -> m.content())
            .contains("safe");
        assertThat(ProfileContext.current()).isNull();
      }
    }
  }

  @Test
  void sensitiveUserNeverStartsAndSensitiveReplyNeverEntersHistory() throws Exception {
    try (var f = new ReactRuntimeFixture();
        var r = f.openRuntime()) {
      String session = r.agents().createSession("p").sessionId();
      String sentinel = "synthetic-runtime-credential-123";
      var rejected = r.agents().process(new AgentRequest(session, "p", sentinel));
      assertThat(rejected.errorCode()).isEqualTo(AgentErrorCode.SENSITIVE_CONTENT);
      assertThat(r.memory().loadSession(session, "p").messages()).isEmpty();
      assertThat(f.httpRequests).hasValue(0);
      f.reply.set(f.reply.get().replace("安全回复", sentinel));
      var result = r.agents().process(new AgentRequest(session, "p", "safe"));
      assertThat(result.errorCode())
          .isEqualTo(
              f.secureReadsSupported()
                  ? AgentErrorCode.SENSITIVE_CONTENT
                  : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      assertThat(result.reply()).isNull();
      assertThat(r.memory().loadSession(session, "p").messages())
          .extracting(m -> m.content())
          .containsExactly("safe");
    }
  }
}

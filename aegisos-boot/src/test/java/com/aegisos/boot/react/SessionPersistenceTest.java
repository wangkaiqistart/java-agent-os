package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionPersistenceTest {
  @Test
  void completedConversationRestoresSameSessionAcrossRuntimeRestart() throws Exception {
    try (var fixture = new ReactRuntimeFixture()) {
      String session;
      try (var runtime = fixture.openRuntime()) {
        session = runtime.agents().createSession("p").sessionId();
        assertThat(UUID.fromString(session).toString()).isEqualTo(session);
        var result = runtime.agents().process(new AgentRequest(session, "p", "第一条用户消息"));
        if (fixture.secureReadsSupported()) {
          assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
          assertThat(result.iterationCount()).isEqualTo(1);
        } else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      }
      try (var runtime = fixture.openRuntime()) {
        var restored = runtime.memory().loadSession(session, "p");
        assertThat(restored.messages()).extracting(m -> m.content()).contains("第一条用户消息");
        var result = runtime.agents().process(new AgentRequest(session, "p", "第二条用户消息"));
        if (fixture.secureReadsSupported()) {
          assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
          assertThat(fixture.httpRequests).hasValue(2);
        } else {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          assertThat(fixture.httpRequests).hasValue(0);
        }
        assertThat(runtime.memory().loadSession(session, "p").messages())
            .extracting(m -> m.content())
            .contains("第一条用户消息", "第二条用户消息");
        assertThat(
                runtime
                    .agents()
                    .process(new AgentRequest(UUID.randomUUID().toString(), "p", "invalid"))
                    .errorCode())
            .isEqualTo(AgentErrorCode.UNKNOWN_SESSION);
      }
    }
  }
}

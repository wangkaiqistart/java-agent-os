package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class ProcessingInterruptionTest {
  @Test
  void interruptedThreadDoesNotSendAndAlwaysClearsProfileContext() throws Exception {
    try (var f = new ReactRuntimeFixture();
        var r = f.openRuntime()) {
      String session = r.agents().createSession("p").sessionId();
      try {
        Thread.currentThread().interrupt();
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        assertThat(result.status()).isEqualTo(ProcessingStatus.INTERRUPTED);
        assertThat(result.errorCode()).isEqualTo(AgentErrorCode.INTERRUPTED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(f.httpRequests).hasValue(0);
        assertThat(ProfileContext.current()).isNull();
      } finally {
        Thread.interrupted();
      }
    }
  }
}

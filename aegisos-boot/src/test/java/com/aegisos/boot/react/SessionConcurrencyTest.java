package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SessionConcurrencyTest {
  @Test
  void sameSessionNeverWaitsOrSendsTwiceWhileOtherSessionsRemainIndependent() throws Exception {
    try (var fixture = new ReactRuntimeFixture();
        var runtime = fixture.openRuntime()) {
      String session = runtime.agents().createSession("p").sessionId();
      if (!fixture.secureReadsSupported()) {
        assertThat(runtime.agents().process(new AgentRequest(session, "p", "one")).errorCode())
            .isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        assertThat(fixture.httpRequests).hasValue(0);
        return;
      }
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      fixture.beforeReply.set(
          () -> {
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("BARRIER_TIMEOUT");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            }
          });
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var first =
            executor.submit(
                () -> runtime.agents().process(new AgentRequest(session, "p", "first")));
        try {
          assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
          var busy = runtime.agents().process(new AgentRequest(session, "p", "second"));
          assertThat(busy.errorCode()).isEqualTo(AgentErrorCode.SESSION_BUSY);
          String other = runtime.agents().createSession("p").sessionId();
          assertThat(other).isNotEqualTo(session);
          assertThat(runtime.memory().loadSession(other, "p").messages()).isEmpty();
          assertThat(fixture.httpRequests).hasValue(1);
        } finally {
          release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(ProcessingStatus.COMPLETED);
      }
      fixture.beforeReply.set(() -> {});
      assertThat(runtime.agents().process(new AgentRequest(session, "p", "third")).status())
          .isEqualTo(ProcessingStatus.COMPLETED);
      assertThat(runtime.memory().loadSession(session, "p").messages())
          .extracting(m -> m.content())
          .contains("first", "third")
          .doesNotContain("second");
      assertThat(ProfileContext.current()).isNull();
    }
  }

  @Test
  void databasePreventsSecondRunningProcessingEvenThroughAnotherServiceInstance() throws Exception {
    try (var fixture = new ReactRuntimeFixture();
        var runtime = fixture.openRuntime()) {
      String session = runtime.agents().createSession("p").sessionId();
      var h = runtime.memory().beginProcessing(session, "p", "one", java.util.UUID.randomUUID());
      assertThatThrownBy(
              () ->
                  runtime
                      .memory()
                      .beginProcessing(session, "p", "two", java.util.UUID.randomUUID()))
          .isInstanceOfSatisfying(
              AgentException.class,
              e -> assertThat(e.code()).isEqualTo(AgentErrorCode.SESSION_BUSY));
      runtime.memory().finish(h, ProcessingStatus.FAILED, AgentErrorCode.INTERRUPTED, null);
      assertThat(runtime.memory().loadSession(session, "p").messages()).hasSize(1);
    }
  }
}

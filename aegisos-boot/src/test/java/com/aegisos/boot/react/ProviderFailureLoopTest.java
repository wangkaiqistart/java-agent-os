package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class ProviderFailureLoopTest {
  @Test
  void providerRetriesAreOneLogicalRoundAndNeverCauseOuterRetry() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      f.providerRetries = 2;
      f.providerReadTimeout = "500ms";
      try (var r = f.openRuntime()) {
        f.beforeReply.set(
            () -> {
              try {
                Thread.sleep(1200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        if (f.secureReadsSupported()) {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.PROVIDER_FAILURE);
          assertThat(result.iterationCount()).isZero();
          try (var c = f.reopen();
              var s = c.createStatement();
              var rows =
                  s.executeQuery(
                      "select error_code,retryable from llm_calls order by attempt_no")) {
            while (rows.next())
              assertThat(rows.getString(1)).isIn("PROVIDER_TIMEOUT", "TEMPORARY_NETWORK_ERROR");
          }

          try (var c = f.reopen();
              var s = c.createStatement();
              var rows =
                  s.executeQuery(
                      "select count(*),count(distinct request_id),max(attempt_no),min(attempt_no) from llm_calls where status='FAILED'")) {
            assertThat(rows.getInt(1)).isEqualTo(3);
            assertThat(rows.getInt(2)).isEqualTo(1);
            assertThat(rows.getInt(3)).isEqualTo(3);
            assertThat(rows.getInt(4)).isEqualTo(1);
          }
          assertThat(f.physicalSends).hasValue(3);
          assertThat(f.httpRequests.get()).isBetween(1, 3);
        } else assertThat(f.httpRequests).hasValue(0);
        assertThat(ProfileContext.current()).isNull();
      }
    }
  }
}

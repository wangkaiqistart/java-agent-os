package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class ProviderToolLoopIntegrationTest {
  @Test
  void multipleCallsAreSerialAndHistoryIsPairedThenNextProviderCompletes() throws Exception {
    try (var f = new ToolLoopFixture()) {
      f.responses.set(
          n ->
              n == 1
                  ? f.response(f.tool("one", "/weather"), f.tool("two", "/other"))
                  : f.response());
      try (var r = f.base.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "天气如何"));
        if (!f.base.secureReadsSupported()) {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          assertThat(f.base.httpRequests).hasValue(0);
          assertThat(f.gets).hasValue(0);
          return;
        }
        assertThat(result.errorCode()).isNull();
        assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(result.iterationCount()).isEqualTo(2);
        assertThat(f.gets).hasValue(2);
        assertThat(f.base.httpRequests).hasValue(2);
        assertThat(f.order).containsExactly("/weather", "/other");
        try (var c = f.base.reopen();
            var s = c.createStatement()) {
          try (var rows =
              s.executeQuery(
                  "select count(*) from tool_invocations where status='SUCCEEDED' and success=1")) {
            assertThat(rows.getInt(1)).isEqualTo(2);
          }
          try (var rows =
              s.executeQuery(
                  "select count(*) from message_exchange_groups where kind='TOOL_EXCHANGE' and state='CLOSED' and committed_observations=2")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
          }
          try (var rows = s.executeQuery("select count(*) from llm_request_links")) {
            assertThat(rows.getInt(1)).isEqualTo(2);
          }
        }
      }
    }
  }

  @Test
  void sameRawIdAcrossResponsesReusesCommittedObservationWithoutSecondGet() throws Exception {
    try (var f = new ToolLoopFixture()) {
      f.responses.set(n -> n < 3 ? f.response(f.tool("same", "/weather")) : f.response());
      try (var r = f.base.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "天气如何"));
        if (f.base.secureReadsSupported()) {
          assertThat(result.errorCode()).isNull();
          assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
          assertThat(result.iterationCount()).isEqualTo(3);
          assertThat(f.gets).hasValue(1);
          assertThat(f.base.httpRequests).hasValue(3);
        } else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      }
    }
  }
}

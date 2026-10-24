package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;

class ContextProjectionIntegrationTest {
  @Test
  void sameRawCallIdAcrossCompletedProcessingsRemainsValidForOriginalMapper() throws Exception {
    try (var f = new ToolLoopFixture()) {
      f.responses.set(n -> n % 2 == 1 ? f.response(f.tool("repeated", "/weather")) : f.response());
      try (var r = f.base.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        for (int i = 0; i < 2; i++) {
          var result = r.agents().process(new AgentRequest(session, "p", "weather"));
          if (f.base.secureReadsSupported())
            assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
          else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        }
        if (f.base.secureReadsSupported()) {
          assertThat(f.gets).hasValue(2);
          assertThat(f.base.httpRequests).hasValue(4);
          try (var c = f.base.reopen();
              var s = c.createStatement();
              var rows =
                  s.executeQuery(
                      "select count(*),count(distinct process_id),count(distinct raw_call_id) from tool_invocations")) {
            assertThat(rows.getInt(1)).isEqualTo(2);
            assertThat(rows.getInt(2)).isEqualTo(2);
            assertThat(rows.getInt(3)).isEqualTo(1);
          }
        }
      }
    }
  }
}

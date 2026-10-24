package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class IterationBoundaryIntegrationTest {
  @Test
  void oneTwoTenKeepLastIntentOpenAndExecuteExactlyNMinusOneTools() throws Exception {
    for (int n : new int[] {1, 2, 10})
      try (var f = new ToolLoopFixture()) {
        Path p = f.base.root.resolve(".aegisos/agents/physical/profile.yml");
        Files.writeString(p, Files.readString(p) + "settings:\n  max_iterations: " + n + "\n");
        f.responses.set(i -> f.response(f.tool("call" + i, "/weather")));
        try (var r = f.base.openRuntime()) {
          String session = r.agents().createSession("p").sessionId();
          var result = r.agents().process(new AgentRequest(session, "p", "safe"));
          if (f.base.secureReadsSupported()) {
            assertThat(result.status()).isEqualTo(ProcessingStatus.MAX_ITERATIONS);
            assertThat(result.iterationCount()).isEqualTo(n);
            assertThat(f.base.httpRequests).hasValue(n);
            assertThat(f.gets).hasValue(n - 1);
            try (var c = f.base.reopen();
                var s = c.createStatement();
                var rows =
                    s.executeQuery(
                        "select state,expected_observations,committed_observations from message_exchange_groups where kind='TOOL_EXCHANGE' order by group_ordinal desc limit 1")) {
              assertThat(rows.getString(1)).isEqualTo("OPEN");
              assertThat(rows.getInt(2)).isEqualTo(1);
              assertThat(rows.getInt(3)).isZero();
            }
          } else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        }
      }
  }
}

package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ContextCapacityBoundaryTest {
  @Test
  void utf8FileAndUserLimitsRejectBeforeAnyProviderSend() throws Exception {
    for (boolean file : new boolean[] {false, true})
      try (var f = new ReactRuntimeFixture();
          var r = f.openRuntime()) {
        if (file)
          Files.writeString(f.root.resolve(".aegisos/agents/physical/AGENT.md"), "中".repeat(11000));
        String session = r.agents().createSession("p").sessionId();
        var result =
            r.agents().process(new AgentRequest(session, "p", file ? "safe" : "中".repeat(11000)));
        assertThat(result.errorCode())
            .isEqualTo(
                !file || f.secureReadsSupported()
                    ? AgentErrorCode.CONTEXT_LIMIT
                    : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        assertThat(f.httpRequests).hasValue(0);
      }
  }
}

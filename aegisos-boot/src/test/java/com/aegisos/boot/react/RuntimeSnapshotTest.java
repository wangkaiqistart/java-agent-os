package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class RuntimeSnapshotTest {
  @Test
  void profileIsStartupSnapshotWhileAgentBodyChangesNextProcessing() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      String session;
      try (var r = f.openRuntime()) {
        session = r.agents().createSession("p").sessionId();
        Path dir = f.root.resolve(".aegisos/agents/physical");
        var first = r.agents().process(new AgentRequest(session, "p", "first"));
        Files.writeString(dir.resolve("AGENT.md"), "正文第二版");
        Files.writeString(
            dir.resolve("profile.yml"),
            "name: changed\nprovider: {name: model-provider, model: test-model}\n");
        var second = r.agents().process(new AgentRequest(session, "p", "second"));
        if (f.secureReadsSupported()) {
          assertThat(first.status()).isEqualTo(ProcessingStatus.COMPLETED);
          assertThat(second.status()).isEqualTo(ProcessingStatus.COMPLETED);
          assertThat(f.modelBodies.getLast()).contains("正文第二版");
        } else assertThat(second.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      }
      try (var r = f.openRuntime()) {
        assertThatThrownBy(() -> r.agents().createSession("p")).hasMessage("UNKNOWN_PROFILE");
        assertThat(r.agents().createSession("changed").profileName()).isEqualTo("changed");
      }
    }
  }
}

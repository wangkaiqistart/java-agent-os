package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class SensitiveIngressIntegrationTest {
  @Test
  void agentBootstrapAndBoundSkillSecretsStopBeforeProviderWithoutPersistingBody()
      throws Exception {
    for (String kind : java.util.List.of("AGENT", "Bootstrap", "Skill"))
      try (var f = new ReactRuntimeFixture()) {
        Path agent = Files.createDirectories(f.root.resolve(".aegisos/agents/physical"));
        String profile = "name: p\nprovider: {name: model-provider, model: test-model}\n";
        String sentinel = "synthetic-runtime-credential-123";
        if (kind.equals("AGENT")) Files.writeString(agent.resolve("AGENT.md"), sentinel);
        if (kind.equals("Bootstrap")) {
          profile += "bootstrap: [SOUL.md]\n";
          Files.writeString(f.root.resolve(".aegisos/SOUL.md"), sentinel);
        }
        if (kind.equals("Skill")) {
          Path target = Files.createDirectories(f.root.resolve(".aegisos/skills/weather"));
          Files.writeString(target.resolve("SKILL.md"), sentinel);
          Path binding = Files.createDirectories(agent.resolve("skills")).resolve("weather");
          Files.createSymbolicLink(binding, binding.getParent().relativize(target));
        }
        Files.writeString(agent.resolve("profile.yml"), profile);
        try (var r = f.openRuntime()) {
          String session = r.agents().createSession("p").sessionId();
          var result = r.agents().process(new AgentRequest(session, "p", "safe"));
          assertThat(result.errorCode())
              .isEqualTo(
                  f.secureReadsSupported()
                      ? AgentErrorCode.SENSITIVE_CONTENT
                      : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          assertThat(result.reply()).isNull();
          assertThat(f.httpRequests).hasValue(0);
          assertThat(r.memory().loadSession(session, "p").messages())
              .extracting(m -> m.content())
              .containsExactly("safe");
        }
      }
  }
}

package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextLoaderTest extends MinimalContextTest {
  Profile with(List<String> bootstrap, List<SkillBinding> skills) {
    var p = profile();
    return new Profile(
        p.name(),
        null,
        p.identity(),
        p.provider(),
        List.of(),
        skills,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        null);
  }

  @Test
  void rereadsBootstrapSkillInStableOrderWithDefaultMissingWarnings() throws Exception {
    var dir = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(dir.resolve("AGENT.md"), "agent");
    var aegis = root.resolve(".aegisos");
    Files.writeString(aegis.resolve("SOUL.md"), "soul");
    var skill = Files.createDirectories(aegis.resolve("skills/weather"));
    Files.writeString(skill.resolve("SKILL.md"), "skill-v1 ${LITERAL}");
    var binding = Files.createDirectories(dir.resolve("skills")).resolve("weather");
    Files.createSymbolicLink(binding, Path.of("../../../skills/weather"));
    var p =
        with(
            List.of(),
            List.of(
                new SkillBinding(
                    "weather",
                    root.relativize(binding).toString(),
                    root.relativize(skill).toString())));
    var source =
        new AgentSource(p.name(), root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader = new ContextLoader(budget, new DefaultSensitiveContentGuard(List.of()));
    if (!secureDirectoryAvailable()) {
      assertThatThrownBy(() -> loader.load(p, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return;
    }
    var loaded = loader.load(p, source);
    assertThat(loaded.instructions()).containsSubsequence("agent", "soul", "skill-v1 ${LITERAL}");
    assertThat(loaded.warnings()).containsExactly("OPTIONAL_FILE_MISSING", "OPTIONAL_FILE_MISSING");
    Files.writeString(skill.resolve("SKILL.md"), "skill-v2");
    assertThat(loader.load(p, source).instructions())
        .contains("skill-v2")
        .doesNotContain("skill-v1");
    var explicit = with(List.of("SOUL.md", "absent.md"), List.of());
    assertThatThrownBy(() -> loader.load(explicit, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
  }

  @Test
  void explicitBootstrapOrderAndInvalidUtf8MissingSkillAreMandatoryFailures() throws Exception {
    var dir = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(dir.resolve("AGENT.md"), "agent");
    Files.writeString(root.resolve(".aegisos/A.md"), "first");
    Files.writeString(root.resolve(".aegisos/B.md"), "second");
    var source =
        new AgentSource("logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader = new ContextLoader(budget, new DefaultSensitiveContentGuard(List.of()));
    var p = with(List.of("B.md", "A.md"), List.of());
    if (!secureDirectoryAvailable()) {
      assertThatThrownBy(() -> loader.load(p, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return;
    }
    assertThat(loader.load(p, source).instructions())
        .containsSubsequence("agent", "second", "first");
    Files.write(root.resolve(".aegisos/A.md"), new byte[] {(byte) 255});
    assertThatThrownBy(() -> loader.load(p, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
  }
}

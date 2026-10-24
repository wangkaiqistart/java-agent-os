package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.SkillBinding;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextPathReplacementTest extends ContextLoaderTest {
  @Test
  void changedBindingAndOptionalIoFailureAreNeverMissingWarnings() throws Exception {
    var dir = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(dir.resolve("AGENT.md"), "agent");
    var one = Files.createDirectories(root.resolve(".aegisos/skills/one"));
    var two = Files.createDirectories(root.resolve(".aegisos/skills/two"));
    Files.writeString(one.resolve("SKILL.md"), "one");
    Files.writeString(two.resolve("SKILL.md"), "two");
    var binding = Files.createDirectories(dir.resolve("skills")).resolve("s");
    Files.createSymbolicLink(binding, binding.getParent().relativize(one));
    var p =
        with(
            List.of(),
            List.of(
                new SkillBinding(
                    "s", root.relativize(binding).toString(), root.relativize(one).toString())));
    var source =
        new AgentSource("logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader = new ContextLoader(budget, new DefaultSensitiveContentGuard(List.of()));
    if (!secureDirectoryAvailable()) {
      assertThatThrownBy(() -> loader.load(p, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return;
    }
    assertThat(loader.load(p, source).instructions()).contains("one");
    Files.delete(binding);
    Files.createSymbolicLink(binding, binding.getParent().relativize(two));
    assertThatThrownBy(() -> loader.load(p, source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
    Files.createDirectory(root.resolve(".aegisos/AGENTS.md"));
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
  }
}

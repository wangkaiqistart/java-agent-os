package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class MinimalContextTest {
  @TempDir Path root;
  final ContextBudget budget = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);

  Profile profile() {
    return new Profile(
        "logical",
        null,
        new Identity("助手", "角色"),
        new ProviderSelection("p", "m", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  boolean secureDirectoryAvailable() throws Exception {
    try (var directory = Files.newDirectoryStream(root)) {
      boolean available = directory instanceof SecureDirectoryStream<?>;
      if (Boolean.getBoolean("aegisos.test.require-secure-directory-stream"))
        assertThat(available).as("Linux 验收必须实际提供安全目录流").isTrue();
      return available;
    }
  }

  @Test
  void rereadsBodyWithStrictUtf8AndRejectsOutsideLink() throws Exception {
    Path dir = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Path file = Files.writeString(dir.resolve("AGENT.md"), "第一版");
    var source =
        new AgentSource("logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader =
        new ContextLoader(
            budget, new DefaultSensitiveContentGuard(List.of("synthetic-file-secret-123")));
    if (!secureDirectoryAvailable()) {
      assertThatThrownBy(() -> loader.load(profile(), source))
          .hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return; // 此平台只验收拒绝；正文读取的正向断言必须在 Linux 强制能力验收中完成。
    }
    assertThat(loader.load(profile(), source).instructions()).contains("第一版");
    Files.writeString(file, "第二版");
    assertThat(loader.load(profile(), source).instructions()).contains("第二版").doesNotContain("第一版");
    Files.write(file, new byte[] {(byte) 0xc0, (byte) 0xaf});
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
    Files.delete(file);
    Path external = Files.createTempFile("outside-react", ".md");
    try {
      Files.writeString(external, "outside");
      Files.createSymbolicLink(file, external);
      assertThatThrownBy(() -> loader.load(profile(), source))
          .hasMessage("CONTEXT_FILE_UNAVAILABLE");
    } finally {
      Files.delete(external);
    }
  }

  @Test
  void missingLargeAndSensitiveRequiredFileFailClosed() throws Exception {
    Path dir = Files.createDirectories(root.resolve(".aegisos/agents/p"));
    var source =
        new AgentSource("logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader =
        new ContextLoader(
            budget, new DefaultSensitiveContentGuard(List.of("synthetic-file-secret-123")));
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
    Files.writeString(dir.resolve("AGENT.md"), "中".repeat(12000));
    if (!secureDirectoryAvailable()) {
      assertThatThrownBy(() -> loader.load(profile(), source))
          .hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return; // 不把平台拒绝误算为容量/敏感正文行为已验证。
    }
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_LIMIT");
    Files.writeString(dir.resolve("AGENT.md"), "synthetic-file-secret-123");
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("SENSITIVE_CONTENT");
  }

  @Test
  void agentInstructionCannotLinkToAnotherAgentInsideSameWorkspace() throws Exception {
    Path directory = Files.createDirectories(root.resolve(".aegisos/agents/selected"));
    Path other = Files.createDirectories(root.resolve(".aegisos/agents/other"));
    Path otherFile = Files.writeString(other.resolve("AGENT.md"), "other agent instructions");
    Files.createSymbolicLink(directory.resolve("AGENT.md"), otherFile);
    var source =
        new AgentSource(
            "logical", root.toRealPath(), directory.toRealPath(), root.relativize(directory));
    var loader = new ContextLoader(budget, new DefaultSensitiveContentGuard(List.of()));
    // 既检查工作区外，也检查同工作区不同 Agent 目录；不能把工作区根当成 AGENT.md 授权根。
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
  }
}

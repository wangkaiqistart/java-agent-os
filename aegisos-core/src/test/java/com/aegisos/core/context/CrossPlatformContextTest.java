package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 三平台都必须读取成功；不能将macOS拒绝当成正向验收。 */
class CrossPlatformContextTest extends MinimalContextTest {
  @Test
  void nativeContextReadsChineseSpacePathAndRereadsCrLf() throws Exception {
    var dir = Files.createDirectories(root.resolve("中文 空格/.aegisos/agents/p"));
    var file = Files.writeString(dir.resolve("AGENT.md"), "第一行\r\n第二行");
    var workspace = root.resolve("中文 空格").toRealPath();
    var source =
        new AgentSource(
            "logical", workspace, dir.toRealPath(), workspace.relativize(dir.toRealPath()));
    var loader = new ContextLoader(budget, new DefaultSensitiveContentGuard(List.of()));
    assertThat(loader.load(profile(), source).instructions()).contains("第一行\r\n第二行");
    Files.writeString(file, "新正文");
    assertThat(loader.load(profile(), source).instructions()).contains("新正文").doesNotContain("第一行");
  }

  @Test
  void nativeReadsEnforceUtf8SizeAndSensitiveGuardOnEveryPlatform() throws Exception {
    var dir = Files.createDirectories(root.resolve(".aegisos/agents/p"));
    var file = dir.resolve("AGENT.md");
    var source =
        new AgentSource("logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir));
    var loader =
        new ContextLoader(
            budget, new DefaultSensitiveContentGuard(List.of("synthetic-file-secret-123")));
    Files.writeString(file, "synthetic-file-secret-123");
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("SENSITIVE_CONTENT");
    Files.writeString(file, "中".repeat(12000));
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_LIMIT");
    Files.write(file, new byte[] {(byte) 255});
    assertThatThrownBy(() -> loader.load(profile(), source)).hasMessage("CONTEXT_FILE_UNAVAILABLE");
  }
}

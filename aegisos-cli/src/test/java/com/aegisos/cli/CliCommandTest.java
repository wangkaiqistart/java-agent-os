package com.aegisos.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliCommandTest {
  @TempDir Path root;

  @Test
  void parsesOnlyFixedCommandsAndSafeOptions() {
    assertThat(
            CliArguments.parse(
                    new String[] {
                      "chat",
                      "--workspace",
                      root.toString(),
                      "--agent",
                      "weather",
                      "--session",
                      "s-1"
                    })
                .session())
        .isEqualTo("s-1");
    for (String[] args :
        new String[][] {
          {"init"},
          {"init", "--workspace", "relative"},
          {"chat", "--workspace", root.toString()},
          {"init", "--workspace", root.toString(), "--workspace", root.toString()},
          {"chat", "--workspace", root.toString(), "--agent", "weather", "--api-key", "sentinel"},
          {"chat", "--workspace", root.toString(), "--agent", "weather", "--session", "bad/session"}
        })
      assertThatThrownBy(() -> CliArguments.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_CLI_ARGUMENTS");
  }

  @Test
  void initIsOfflineAndNeverOverwritesExistingContent() throws Exception {
    new InitCommand().run(root);
    Path config = root.resolve(".aegisos/config.yml");
    String original = Files.readString(config);
    assertThat(original).contains("targets: []", "env:DEEPSEEK_API_KEY");
    assertThat(Files.exists(root.resolve(".aegisos/aegisos.db"))).isFalse();
    assertThatThrownBy(() -> new InitCommand().run(root)).hasMessage("INIT_REFUSED");
    assertThat(Files.readString(config)).isEqualTo(original);
  }

  @Test
  void refusesSymlinkAndPartialExistingWorkspaceWithoutChanges() throws Exception {
    Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
    Files.createSymbolicLink(root.resolve(".aegisos"), elsewhere);
    assertThatThrownBy(() -> new InitCommand().run(root)).hasMessage("INIT_REFUSED");
    try (var paths = Files.list(elsewhere)) {
      assertThat(paths.count()).isZero();
    }
  }
}

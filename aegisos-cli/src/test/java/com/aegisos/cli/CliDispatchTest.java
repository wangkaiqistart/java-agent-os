package com.aegisos.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 帮助必须在任何运行时创建前被识别；错误只保留固定诊断。 */
class CliDispatchTest {
  @TempDir Path root;

  @Test
  void rootHelpAndVersionAreControlCommands() {
    assertThat(CliArguments.parse(new String[] {"--help"}).command()).isEqualTo("help");
    assertThat(CliArguments.parse(new String[] {"--version"}).command()).isEqualTo("version");
    assertThat(CliArguments.parse(new String[] {"profile", "list", "--help"}).command())
        .isEqualTo("help");
  }

  @Test
  void nestedCommandsAndProfileAliasAreRecognized() {
    assertThat(
            CliArguments.parse(new String[] {"profile", "list", "--workspace", root.toString()})
                .command())
        .isEqualTo("profile list");
    assertThat(
            CliArguments.parse(
                    new String[] {"chat", "--workspace", root.toString(), "--profile", "weather"})
                .agent())
        .isEqualTo("weather");
  }

  @Test
  void aliasesAndDuplicatesCannotOverrideEarlierValues() {
    for (String[] args :
        new String[][] {
          {"chat", "--workspace", root.toString(), "--profile", "weather", "--agent", "weather"},
          {
            "chat", "--workspace", root.toString(), "--agent", "weather", "--interactive", "--batch"
          },
          {"--unrecognized-synthetic-value"}
        }) assertThatThrownBy(() -> CliArguments.parse(args)).hasMessage("INVALID_CLI_ARGUMENTS");
  }
}

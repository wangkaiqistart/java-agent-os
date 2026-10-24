package com.aegisos.cli;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatModeTest {
  CliArguments parse(String... extra) {
    var args =
        new java.util.ArrayList<>(
            java.util.List.of(
                "chat",
                "--workspace",
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                    .toAbsolutePath()
                    .toString(),
                "--profile",
                "p"));
    args.addAll(java.util.List.of(extra));
    return CliArguments.parse(args.toArray(String[]::new));
  }

  @Test
  void explicitModesOverrideTerminalAndPipeDefaultStaysBatch() {
    assertThat(parse().interactive(true)).isTrue();
    assertThat(parse().interactive(false)).isFalse();
    assertThat(parse("--interactive").interactive(false)).isTrue();
    assertThat(parse("--batch").interactive(true)).isFalse();
  }

  @Test
  void conflictingUserAndModesAreRejected() {
    for (String[] extra :
        new String[][] {
          {"--batch", "--user", "local"},
          {"--session", "s-1", "--user", "local"},
          {"--interactive", "--batch"},
          {"--user", "bad value"}
        }) assertThatThrownBy(() -> parse(extra)).hasMessage("INVALID_CLI_ARGUMENTS");
  }
}

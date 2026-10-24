package com.aegisos.cli;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliCommandTreeTest {
  @Test
  void exactlyTwelveLeavesHaveOfflineHelp() {
    var cli = AegisOsCli.commandLine();
    var paths = new ArrayList<String>();
    cli.getSubcommands()
        .forEach(
            (name, command) -> {
              if (command.getSubcommands().isEmpty()) paths.add(name);
              else
                command.getSubcommands().keySet().forEach(child -> paths.add(name + " " + child));
            });
    assertThat(paths).hasSize(12).contains("profile delete", "session list", "gateway");
    for (String path : paths) {
      var args = new ArrayList<>(List.of(path.split(" ")));
      args.add("--help");
      var parsed = CliArguments.parse(args.toArray(String[]::new));
      assertThat(parsed.command()).isEqualTo("help");
      var bytes = new ByteArrayOutputStream();
      AegisOsCli.printHelp(parsed.options().get("help-path"), new PrintStream(bytes));
      assertThat(bytes.toString()).contains("Usage:");
    }
  }

  @Test
  void repeatableFlagsAndForeignOptionsAreRejected() {
    for (String[] args :
        new String[][] {
          {
            "chat",
            "--workspace",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString(),
            "--profile",
            "p",
            "--interactive",
            "--interactive"
          },
          {
            "init",
            "--workspace",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString(),
            "--port",
            "1"
          }
        }) assertThatThrownBy(() -> CliArguments.parse(args)).hasMessage("INVALID_CLI_ARGUMENTS");
  }

  @org.junit.jupiter.api.Test
  void helpOnlyShowsSupportedOptionsAndRequiredArguments() {
    var root = AegisOsCli.commandLine();
    var delete = root.getSubcommands().get("profile").getSubcommands().get("delete");
    assertThat(delete.getCommandSpec().findOption("--batch")).isNull();
    assertThat(delete.getCommandSpec().findOption("--port")).isNull();
    assertThat(delete.getCommandSpec().findOption("--name").required()).isTrue();
    assertThat(delete.getCommandSpec().findOption("--workspace").required()).isTrue();
    assertThat(root.getSubcommands().get("serve").getCommandSpec().findOption("--user")).isNull();
    assertThat(root.getSubcommands().get("chat").getCommandSpec().findOption("--profile"))
        .isNotNull();
  }
}

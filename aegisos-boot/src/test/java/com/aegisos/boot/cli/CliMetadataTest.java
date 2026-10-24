package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.AegisOsApplication;
import com.aegisos.cli.InitCommand;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliMetadataTest {
  @TempDir Path root;

  record Result(int exit, String out, String err) {}

  Result run(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit =
        AegisOsApplication.dispatch(
            args,
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(out),
            new PrintStream(err));
    return new Result(
        exit,
        out.toString(java.nio.charset.StandardCharsets.UTF_8),
        err.toString(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void metadataWithoutCredentialsNeverCreatesDatabase() throws Exception {
    new InitCommand().run(root);
    for (String[] command :
        new String[][] {
          {"profile", "list"},
          {"profile", "show", "--name", "weather"},
          {"provider", "list"},
          {"tool", "list"}
        }) {
      var args = new java.util.ArrayList<String>(java.util.List.of(command));
      args.add("--workspace");
      args.add(root.toString());
      var result = run(args.toArray(String[]::new));
      assertThat(result.exit()).as(java.util.Arrays.toString(command)).isZero();
      assertThat(result.out()).doesNotContain("credential-ref", "base-url");
    }
    assertThat(Files.exists(root.resolve(".aegisos/aegisos.db"))).isFalse();
    assertThat(Files.exists(root.resolve(".aegisos/aegisos.db.lock"))).isFalse();
  }

  @Test
  void malformedProfileDoesNotHideValidOneOrEchoYaml() throws Exception {
    new InitCommand().run(root);
    var bad = Files.createDirectory(root.resolve(".aegisos/agents/broken"));
    Files.writeString(bad.resolve("profile.yml"), "name: broken\nname: forbidden-raw-payload\n");
    var result = run("profile", "list", "--workspace", root.toString());
    assertThat(result.exit()).isEqualTo(2);
    assertThat(result.out())
        .contains("weather", "diagnostics")
        .doesNotContain("forbidden-raw-payload");
  }
}

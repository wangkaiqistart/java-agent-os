package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.AegisOsApplication;
import com.aegisos.cli.InitCommand;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProfileDefinitionTest {
  @TempDir Path root;

  int run(String... words) {
    var args = new java.util.ArrayList<String>(java.util.List.of(words));
    args.add("--workspace");
    args.add(root.toString());
    return AegisOsApplication.dispatch(
        args.toArray(String[]::new),
        new ByteArrayInputStream(new byte[0]),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()));
  }

  @Test
  void createWithoutOverwriteThenAtomicDeletePreservesSharedSkillAndHistory() throws Exception {
    new InitCommand().run(root);
    var old = new WorkspaceMetadataService(root);
    var common = Files.createDirectory(root.resolve(".aegisos/skills/shared"));
    Files.writeString(common.resolve("SKILL.md"), "shared");
    assertThat(
            run(
                "profile",
                "create",
                "--name",
                "demo",
                "--provider",
                "deepseek-main",
                "--model",
                "deepseek-flash"))
        .isZero();
    var definition = root.resolve(".aegisos/agents/demo/profile.yml");
    var bytes = Files.readAllBytes(definition);
    assertThat(
            run(
                "profile",
                "create",
                "--name",
                "demo",
                "--provider",
                "deepseek-main",
                "--model",
                "deepseek-flash"))
        .isEqualTo(2);
    assertThat(Files.readAllBytes(definition)).isEqualTo(bytes);
    Files.createSymbolicLink(
        root.resolve(".aegisos/agents/demo/skills/shared"), Path.of("../../../skills/shared"));
    Files.writeString(root.resolve(".aegisos/history-sentinel"), "unchanged");
    assertThat(run("profile", "delete", "--name", "demo")).isZero();
    assertThat(Files.exists(definition)).isFalse();
    assertThat(Files.readString(common.resolve("SKILL.md"))).isEqualTo("shared");
    assertThat(Files.readString(root.resolve(".aegisos/history-sentinel"))).isEqualTo("unchanged");
    assertThat(old.report().profiles()).hasSize(1);
    try (var files = Files.list(root.resolve(".aegisos/deleted-agents"))) {
      assertThat(files.toList()).hasSize(1);
    }
    assertThat(Files.exists(root.resolve(".aegisos/aegisos.db"))).isFalse();
  }

  @Test
  void unknownSelectionsAndSymlinkedDestinationRefuseWithoutTouchingTarget() throws Exception {
    new InitCommand().run(root);
    assertThat(run("profile", "create", "--name", "demo", "--provider", "unknown", "--model", "x"))
        .isEqualTo(2);
    assertThat(Files.exists(root.resolve(".aegisos/agents/demo"))).isFalse();
    var outside = Files.createTempDirectory(root, "outside");
    Files.createSymbolicLink(root.resolve(".aegisos/agents/linked"), outside);
    assertThat(
            run(
                "profile",
                "create",
                "--name",
                "linked",
                "--provider",
                "deepseek-main",
                "--model",
                "deepseek-flash"))
        .isEqualTo(2);
    assertThat(Files.list(outside).toList()).isEmpty();
    Files.createSymbolicLink(root.resolve(".aegisos/deleted-agents"), outside);
    assertThat(run("profile", "delete", "--name", "weather")).isEqualTo(2);
    assertThat(Files.isDirectory(root.resolve(".aegisos/agents/weather"))).isTrue();
  }
}

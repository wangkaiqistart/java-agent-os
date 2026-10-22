package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 用真实相对软链接检验边界；技能/脚本/bootstrap 的正文无需存在或可读。 */
class ProfilePathSafetyTest {
  @TempDir Path dir;

  @Test
  void relativeSkillsAreSortedAndBodiesNeverLoaded() throws Exception {
    Path a =
        CourseAlignmentFixtures.agent(
            dir, "one", ProfileLoaderTest.MINIMAL + "bootstrap: [missing.md]\n");
    Path target = dir.resolve(".aegisos/skills/common");
    Files.createDirectories(target);
    Path skills = Files.createDirectory(a.resolve("skills"));
    for (String name : java.util.List.of("z", "a"))
      Files.createSymbolicLink(skills.resolve(name), skills.relativize(target));
    var report = new ProfileLoader(dir, ProfileLoaderTest.providers(), n -> null).load();
    assertThat(report.profiles()).hasSize(1);
    assertThat(report.profiles().getFirst().skills())
        .extracting(SkillBinding::name)
        .containsExactly("a", "z");
  }

  @Test
  void unsafeBindingsAreRejected() throws Exception {
    for (String kind :
        java.util.List.of("absolute", "broken", "cycle", "escape", "regular", "file")) {
      Path root = Files.createDirectory(dir.resolve(kind));
      Path a = CourseAlignmentFixtures.agent(root, "one", ProfileLoaderTest.MINIMAL);
      Path common = root.resolve(".aegisos/skills/common");
      Files.createDirectories(common);
      Path skills = Files.createDirectory(a.resolve("skills"));
      Path link = skills.resolve("binding");
      switch (kind) {
        case "absolute" -> Files.createSymbolicLink(link, common);
        case "broken" -> Files.createSymbolicLink(link, Path.of("missing"));
        case "cycle" -> Files.createSymbolicLink(link, Path.of("binding"));
        case "escape" -> Files.createSymbolicLink(link, skills.relativize(dir));
        case "regular" -> Files.writeString(link, "not a link");
        case "file" -> {
          Path file = Files.writeString(common.resolve("file"), "not a directory");
          Files.createSymbolicLink(link, skills.relativize(file));
        }
      }
      var report = new ProfileLoader(root, ProfileLoaderTest.providers(), n -> null).load();
      assertThat(report.profiles()).isEmpty();
      assertThat(report.diagnostics())
          .extracting(ProfileDiagnostic::code)
          .contains(ProfileDiagnostic.Code.INVALID_SKILL_BINDING);
    }
  }

  @Test
  void escapingAgentRootProfileAndSkillsContainerAreRejected() throws Exception {
    Path outside = Files.createDirectory(dir.resolve("outside"));
    for (String kind : java.util.List.of("agent", "profile", "skills")) {
      Path root = Files.createDirectory(dir.resolve(kind));
      Path a = CourseAlignmentFixtures.agent(root, "one", ProfileLoaderTest.MINIMAL);
      Files.createDirectories(root.resolve(".aegisos/skills"));
      if (kind.equals("agent")) {
        Files.delete(a.resolve("profile.yml"));
        Files.delete(a);
        Files.createSymbolicLink(a, outside);
      }
      if (kind.equals("profile")) {
        Files.delete(a.resolve("profile.yml"));
        Path target = Files.writeString(outside.resolve("profile.yml"), ProfileLoaderTest.MINIMAL);
        Files.createSymbolicLink(a.resolve("profile.yml"), target);
      }
      if (kind.equals("skills")) Files.createSymbolicLink(a.resolve("skills"), outside);
      assertThat(
              new ProfileLoader(root, ProfileLoaderTest.providers(), n -> null).load().profiles())
          .isEmpty();
    }
    Path root = Files.createDirectory(dir.resolve("root"));
    Files.createDirectories(root.resolve(".aegisos"));
    Files.createSymbolicLink(root.resolve(".aegisos/agents"), outside);
    assertThatThrownBy(
            () -> new ProfileLoader(root, ProfileLoaderTest.providers(), n -> null).load())
        .hasMessage("READ_FAILED")
        .hasNoCause();
  }
}

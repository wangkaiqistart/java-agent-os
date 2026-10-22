package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.config.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 全字段、冲突隔离与安全 YAML 都通过临时目录驱动；不读取管理员实际配置。 */
class ProfileLoaderTest {
  @TempDir Path dir;
  static final String MINIMAL = "name: agent-a\nprovider: {name: p, model: m}\n";

  static ProviderRegistry providers() {
    return new ProviderRegistry(
        List.of(
            new ProviderRegistration(
                new ProviderDefinition(
                    "p",
                    "openai-compatible",
                    Set.of("m"),
                    URI.create("https://example.invalid"),
                    "env:TEST_KEY",
                    0,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)),
                null,
                ProviderErrorCode.PROVIDER_UNAVAILABLE)));
  }

  ProfileLoadReport load() {
    return new ProfileLoader(dir, providers(), n -> null).load();
  }

  @Test
  void allFieldsDefaultsAndMissingCredentialsRemainMetadata() throws Exception {
    CourseAlignmentFixtures.agent(
        dir,
        "first",
        MINIMAL
            + "description: 课程元数据\nidentity: {agent_name: assistant, prompt: '${LITERAL}'}\ntools: [lookup]\nmcp_servers: [local]\nchannels: [{name: cli, config: {nested: {enabled: true, labels: [a, b]}}}]\nnotify_channels: [team]\nschedules: [{key: daily, cron: '0 0 8 * * *', zone: Asia/Shanghai, message: check}]\nbootstrap: [SOUL.md]\nsettings: {max_iterations: 10, max_history_turns: 20, custom: {nullable: null}}\n");
    var report = load();
    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.profiles()).hasSize(1);
    var p = report.profiles().getFirst();
    assertThat(p.name()).isEqualTo("agent-a");
    assertThat(p.identity().prompt()).isEqualTo("${LITERAL}");
    assertThat(p.provider().temperature()).isNull();
    assertThat(p.tools()).containsExactly("lookup");
    assertThat(p.skills()).isEmpty();
    assertThat(p.notifyChannels()).containsExactly("team");
    assertThat(p.schedules().getFirst().zone()).isEqualTo("Asia/Shanghai");
    assertThat(p.settings().maxIterations()).isEqualTo(10);
    assertThat(p.settings().extensions()).containsKey("custom");
  }

  @Test
  void invalidCandidatesWithSafeNamesParticipateInConflict() throws Exception {
    CourseAlignmentFixtures.agent(dir, "one", MINIMAL);
    CourseAlignmentFixtures.agent(dir, "two", MINIMAL + "skills: []\n");
    CourseAlignmentFixtures.agent(dir, "other", MINIMAL.replace("agent-a", "other"));
    var r = load();
    assertThat(r.profiles()).extracting(Profile::name).containsExactly("other");
    assertThat(r.diagnostics()).anyMatch(d -> d.code() == ProfileDiagnostic.Code.DUPLICATE_PROFILE);
  }

  @Test
  void badFieldsAreIsolatedAndNeverAppearInDiagnostics() throws Exception {
    int n = 0;
    for (String extra :
        List.of(
            "unknown: sentinel-secret\n",
            "skills: []\n",
            "identity: {unknown: sentinel-secret}\n",
            "provider: {name: absent, model: m}\n",
            "provider: {name: p, model: unavailable}\n",
            "channels: [{name: cli, config: {api_key: sentinel-secret}}]\n",
            "settings: {max_iterations: 0}\n",
            "bootstrap: [../secret]\n")) {
      // provider 反例替换固定字段，避免用重复键掩盖字段错误。
      String yaml = extra.startsWith("provider:") ? "name: agent-a\n" + extra : MINIMAL + extra;
      CourseAlignmentFixtures.agent(
          dir, "bad" + (n++), yaml.replace("name: agent-a", "name: bad" + n));
    }
    CourseAlignmentFixtures.agent(dir, "good", MINIMAL);
    var r = load();
    assertThat(r.profiles()).hasSize(1);
    assertThat(r.diagnostics()).hasSize(n);
    assertThat(r.toString() + r.diagnostics()).doesNotContain("sentinel-secret");
  }

  @Test
  void unsafeYamlVariantsAreRejectedWithoutFragments() throws Exception {
    var bad =
        List.of(
            MINIMAL + "name: duplicate\n",
            "name: &anchor agent-a\nprovider: {name: p, model: m}\n",
            MINIMAL + "description: !!binary c2VjcmV0\n",
            MINIMAL + "description: 2026-01-01\n",
            MINIMAL + "unknown: !!java.lang.ProcessBuilder {}\n",
            MINIMAL + "---\nname: other\n",
            "? [a,b]\n: value\n",
            MINIMAL + "identity: {<<: {prompt: x}}\n",
            MINIMAL + "tools: [*missing]\n",
            MINIMAL + "description: " + "x".repeat(262144),
            MINIMAL + "settings: {tree: " + "[".repeat(40) + "x" + "]".repeat(40) + "}\n",
            MINIMAL + "tools: [" + String.join(",", Collections.nCopies(257, "a")) + "]\n");
    int n = 0;
    for (String yaml : bad) CourseAlignmentFixtures.agent(dir, "bad" + (n++), yaml);
    var r = load();
    assertThat(r.profiles()).isEmpty();
    assertThat(r.diagnostics()).hasSize(bad.size());
    assertThat(r.diagnostics().toString()).doesNotContain("ProcessBuilder", "c2VjcmV0");
  }

  @Test
  void missingRootAndNoFrontmatterFallback() throws Exception {
    assertThat(load().profiles()).isEmpty();
    Path a = dir.resolve(".aegisos/agents/instructions");
    Files.createDirectories(a);
    Files.writeString(a.resolve("AGENT.md"), "---\n" + MINIMAL + "---");
    assertThat(load().profiles()).isEmpty();
    assertThat(load().diagnostics())
        .extracting(ProfileDiagnostic::code)
        .contains(ProfileDiagnostic.Code.PROFILE_FILE_MISSING);
  }
}

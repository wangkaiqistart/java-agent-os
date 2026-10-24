package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 默认测试只构造合成声明、额度和证据；即使环境有凭据也不能启用真实 profile。 */
class ReactSmokeEvidenceTest {
  @TempDir Path root;

  private Path config() throws Exception {
    String yaml = Files.readString(Path.of("../specs/003-react-loop/contracts/acceptance.md"));
    int block = yaml.indexOf("run-id: react-");
    String sample =
        yaml.substring(block, yaml.indexOf("```", block))
            .replace("react-20261022-example", "offline-evidence")
            .replace("/绝对路径/隔离验收目录", root.toString());
    Path file = root.resolve("smoke.yml");
    Files.writeString(file, sample);
    return file;
  }

  @Test
  void strictConfigurationAndGeneratedRuntimeRemainOffline() throws Exception {
    Path source = config();
    var approved = RealReactSmokeConfiguration.read(source);
    assertThat(approved.fingerprint).hasSize(64);
    // 必须绑定显式模式；旧版不含此字段的声明不能沿用同一份证据指纹。
    var legacy = new TreeMap<>(approved.tree);
    legacy.remove("workspace-parent");
    var provider = RealReactSmokeConfiguration.map(legacy, "provider");
    provider.remove("thinking-mode");
    legacy.put("provider", provider);
    String previousFingerprint =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        RealReactSmokeConfiguration.canonical(legacy)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(approved.fingerprint).isNotEqualTo(previousFingerprint);
    Path workspace = Files.createDirectory(root.resolve("workspace"));
    ReactWeatherSmokeIT.configure(workspace, approved);
    assertThat(new com.aegisos.boot.config.CliWorkspaceConfiguration().load(workspace))
        .containsEntry("aegisos.providers[0].max-retries", 0)
        .containsEntry("aegisos.providers[0].thinking-mode", "disabled");
    assertThat(Files.exists(workspace.resolve(".aegisos/aegisos.db"))).isFalse();
    String text = Files.readString(source);
    Files.writeString(source, text + "unknown: true\n");
    assertThatThrownBy(() -> RealReactSmokeConfiguration.read(source))
        .hasMessage("INVALID_REACT_SMOKE_CONFIGURATION");
  }

  @Test
  void explicitDisabledThinkingIsMandatoryForTheCourseRun() throws Exception {
    Path source = config();
    String original = Files.readString(source);
    for (String value : List.of("enabled", "DISABLED", "null", "{}", "''")) {
      Files.writeString(
          source, original.replace("thinking-mode: disabled", "thinking-mode: " + value));
      assertThatThrownBy(() -> RealReactSmokeConfiguration.read(source))
          .hasMessage("INVALID_REACT_SMOKE_CONFIGURATION");
    }
    Files.writeString(source, original.replace("  thinking-mode: disabled\n", ""));
    assertThatThrownBy(() -> RealReactSmokeConfiguration.read(source))
        .hasMessage("INVALID_REACT_SMOKE_CONFIGURATION");
  }

  @Test
  void budgetIsDeductedBeforeAnyAdditionalAction() {
    var budget = new RealReactSmokeBudget(3, 6, 2);
    UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
    for (UUID process : List.of(p1, p2))
      for (int i = 0; i < 3; i++) {
        budget.model(process);
        budget.physical();
      }
    assertThatThrownBy(() -> budget.model(p1)).hasMessage("REACT_SMOKE_BUDGET_EXHAUSTED");
    assertThatThrownBy(budget::physical).hasMessage("REACT_SMOKE_BUDGET_EXHAUSTED");
    budget.get();
    budget.get();
    assertThatThrownBy(budget::get).hasMessage("REACT_SMOKE_BUDGET_EXHAUSTED");
    assertThat(budget.logical).isEqualTo(6);
    assertThat(budget.physical).isEqualTo(6);
    assertThat(budget.gets).isEqualTo(2);
  }

  @Test
  void targetsMustUseApprovedExactCoordinates() throws Exception {
    var approved = RealReactSmokeConfiguration.read(config());
    String url =
        "https://api.open-meteo.com/v1/forecast?latitude=31.230&longitude=121.47&current=temperature_2m,weather_code,wind_speed_10m&timezone=Asia%2FShanghai";
    var mapper = new tools.jackson.databind.ObjectMapper();
    ReactWeatherSmokeIT.validateTarget(mapper.writeValueAsString(Map.of("url", url)), approved);
    for (String bad :
        List.of(
            url.replace("31.230", "32.230"),
            url + "&latitude=31.23",
            url.replace("open-meteo.com", "example.com"),
            url + "#fragment"))
      assertThatThrownBy(
              () ->
                  ReactWeatherSmokeIT.validateTarget(
                      mapper.writeValueAsString(Map.of("url", bad)), approved))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledHarnessNeverSkipsOrCallsExternalServices() throws Exception {
    assertThat(Boolean.getBoolean("aegisos.react-smoke.enabled")).isFalse();
    assertThatThrownBy(() -> new ReactWeatherSmokeIT().weatherConversation())
        .isInstanceOf(AssertionError.class)
        .hasMessage("REACT_SMOKE_NOT_RUN");
    var json =
        new tools.jackson.databind.ObjectMapper()
            .readTree(Files.readString(Path.of("target/react-smoke-result.json")));
    assertThat(json.path("status").asText()).isEqualTo("NOT_RUN");
    assertThat(json.path("physicalSends").asInt()).isZero();
    assertThat(json.path("weatherGets").asInt()).isZero();
  }

  @Test
  void realProfileIsIsolatedAndNotActiveByDefault() throws Exception {
    var doc =
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(Path.of("pom.xml").toFile());
    var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
    String expr =
        "string(/*[local-name()='project']/*[local-name()='profiles']/*[local-name()='profile'][*[local-name()='id']='real-react-smoke']/*[local-name()='activation']/*[local-name()='activeByDefault'])";
    assertThat(xpath.evaluate(expr, doc)).isEmpty();
    String pom = Files.readString(Path.of("pom.xml"));
    assertThat(pom)
        .contains(
            "react-smoke-failsafe-reports",
            "**/ReactWeatherSmokeIT.java",
            "<classesDirectory>${project.build.outputDirectory}</classesDirectory>");
  }
}

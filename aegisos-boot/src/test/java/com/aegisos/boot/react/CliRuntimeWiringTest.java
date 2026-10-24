package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.config.*;
import com.aegisos.cli.InitCommand;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CliRuntimeWiringTest {
  @TempDir Path root;

  @Test
  void workspaceIsTheOnlyConfigSourceAndAuditIsAnchored() throws Exception {
    new InitCommand().run(root);
    var properties = new CliWorkspaceConfiguration().load(root);
    assertThat(properties.get("aegisos.audit.path"))
        .isEqualTo(root.toRealPath().resolve(".aegisos/aegisos.db").toString());
    assertThat(properties.get("aegisos.workspace-root")).isEqualTo(root.toRealPath().toString());
    Files.writeString(
        root.resolve(".aegisos/config.yml"), "spring: {config: {import: 'http://invalid'}}");
    assertThatThrownBy(() -> new CliWorkspaceConfiguration().load(root))
        .hasMessage("INVALID_WORKSPACE_CONFIGURATION");
  }

  @Test
  void thinkingModeUsesTheStrictWorkspaceYamlBoundary() throws Exception {
    new InitCommand().run(root);
    Path file = root.resolve(".aegisos/config.yml");
    String original = Files.readString(file);
    assertThat(new CliWorkspaceConfiguration().load(root))
        .doesNotContainKey("aegisos.providers[0].thinking-mode");
    Files.writeString(
        file,
        original.replace(
            "      max-retries: 0", "      thinking-mode: disabled\n      max-retries: 0"));
    assertThat(new CliWorkspaceConfiguration().load(root))
        .containsEntry("aegisos.providers[0].thinking-mode", "disabled");
    for (String value : java.util.List.of("enabled", "bad", "DISABLED", "null", "{}", "[]", "''")) {
      Files.writeString(
          file,
          original.replace(
              "      max-retries: 0", "      thinking-mode: " + value + "\n      max-retries: 0"));
      assertThatThrownBy(() -> new CliWorkspaceConfiguration().load(root))
          .hasMessage("INVALID_WORKSPACE_CONFIGURATION");
    }
  }

  @Test
  void dedicatedSourceHasNoWebOrComponentScanning() {
    assertThat(
            CliApplicationConfiguration.class.getAnnotation(
                org.springframework.context.annotation.ComponentScan.class))
        .isNull();
    assertThat(
            CliApplicationConfiguration.class.getAnnotation(
                org.springframework.boot.autoconfigure.SpringBootApplication.class))
        .isNull();
  }

  @Test
  void dedicatedRuntimeClosesStorageAndDoesNotCreateWebBeans() throws Exception {
    try (var fixture = new com.aegisos.boot.support.ReactRuntimeFixture()) {
      fixture.customize =
          c -> {
            c.getEnvironment()
                .getPropertySources()
                .addFirst(
                    new org.springframework.core.env.MapPropertySource(
                        "cli-test", java.util.Map.of("aegisos.cli.enabled", "true")));
            c.register(CliApplicationConfiguration.class);
          };
      com.zaxxer.hikari.HikariDataSource source;
      try (var runtime = fixture.openRuntime()) {
        assertThat(runtime.context())
            .isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
        assertThat(
                runtime
                    .context()
                    .getBeansOfType(org.springframework.boot.web.server.WebServer.class))
            .isEmpty();
        assertThat(runtime.context().getBean(com.aegisos.cli.ChatCommand.class)).isNotNull();
        source = runtime.context().getBean(com.zaxxer.hikari.HikariDataSource.class);
      }
      assertThat(source.isClosed()).isTrue();
      try (var reopened = fixture.openRuntime()) {
        assertThat(reopened.agents()).isNotNull();
      }
    }
  }
}

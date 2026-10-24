package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.provider.config.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ProfileSourceMappingTest {
  @TempDir Path root;

  @Test
  void logicalNameDoesNotDetermineDirectoryAndDuplicatesLeaveNoSource() throws Exception {
    Path agent = Files.createDirectories(root.resolve(".aegisos/agents/different-directory"));
    Files.writeString(
        agent.resolve("profile.yml"), "name: logical\nprovider:\n  name: p\n  model: m\n");
    try (var registry =
        new ProviderRegistry(
            List.of(
                new ProviderRegistration(
                    new ProviderDefinition(
                        "p",
                        "openai-compatible",
                        Set.of("m"),
                        java.net.URI.create("https://example.com"),
                        "env:KEY",
                        0,
                        java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1)),
                    null,
                    com.aegisos.core.provider.ProviderErrorCode.PROVIDER_UNAVAILABLE)))) {
      var loader = new ProfileLoader(root, registry, n -> null);
      var report = loader.load();
      assertThat(report.profiles()).hasSize(1);
      assertThat(report.sources().require("logical").agentDirectory())
          .isEqualTo(agent.toRealPath());
      Path duplicate = Files.createDirectories(root.resolve(".aegisos/agents/duplicate"));
      Files.copy(agent.resolve("profile.yml"), duplicate.resolve("profile.yml"));
      report = loader.load();
      assertThat(report.profiles()).isEmpty();
      assertThat(report.sources().sources()).isEmpty();
    }
  }
}

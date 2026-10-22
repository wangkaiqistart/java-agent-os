package com.aegisos.provider.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderDefinitionTest {
  @Test
  void modelSetIsImmutableAndPrintableDefinitionIsSafe() {
    var models = new HashSet<>(Set.of("m"));
    var d =
        new ProviderDefinition(
            "p",
            "openai-compatible",
            models,
            URI.create("https://example.org"),
            "env:KEY",
            0,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    models.clear();
    assertThat(d.models()).containsExactly("m");
    assertThat(d.toString()).doesNotContain("example.org", "KEY");
    assertThatThrownBy(() -> new ProviderRegistration(d, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new ProviderRegistration(d, null, ProviderErrorCode.INVALID_CONFIGURATION).available())
        .isFalse();
  }
}

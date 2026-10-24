package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.ContextBudget;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class SandboxPropertiesTest {
  final ContextBudget b = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);

  @Test
  void defaultsDenyAllWithIndependentTimeouts() {
    var p = new SandboxProperties(null).policy(b);
    assertThat(p.targets()).isEmpty();
    assertThat(p.dns()).isEqualTo(Duration.ofSeconds(2));
    assertThat(p.execution()).isEqualTo(Duration.ofSeconds(10));
    assertThat(p.maxResponseBytes()).isEqualTo(8192);
  }

  @Test
  void invalidTimeoutCapacityAndWildcardTargetsFailAtStartup() {
    for (Duration duration : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(31)))
      assertThatThrownBy(
              () ->
                  new SandboxProperties(
                          new SandboxProperties.HttpGet(duration, null, null, null, null, null))
                      .policy(b))
          .hasMessage("INVALID_SANDBOX_CONFIGURATION");
    assertThatThrownBy(
            () ->
                new SandboxProperties(
                        new SandboxProperties.HttpGet(
                            null, null, null, Duration.ofSeconds(1), null, null))
                    .policy(b))
        .hasMessage("INVALID_SANDBOX_CONFIGURATION");
    assertThatThrownBy(
            () ->
                new SandboxProperties(
                        new SandboxProperties.HttpGet(null, null, null, null, 20000, null))
                    .policy(b))
        .hasMessage("INVALID_SANDBOX_CONFIGURATION");
    var target =
        new SandboxProperties.Target(
            "https", "*.example.org", 443, "GET", List.of("/weather"), List.of("q"));
    assertThatThrownBy(
            () ->
                new SandboxProperties(
                        new SandboxProperties.HttpGet(
                            null, null, null, null, null, List.of(target)))
                    .policy(b))
        .hasMessage("INVALID_SANDBOX_CONFIGURATION");
  }

  @Test
  void startupListsAreDefensivelyFrozen() {
    var paths = new java.util.ArrayList<>(List.of("/weather"));
    var keys = new java.util.ArrayList<>(List.of("q"));
    var target = new SandboxProperties.Target("https", "example.org", 443, "GET", paths, keys);
    var targets = new java.util.ArrayList<>(List.of(target));
    var http = new SandboxProperties.HttpGet(null, null, null, null, null, targets);
    paths.clear();
    keys.clear();
    targets.clear();
    assertThat(http.targets()).hasSize(1);
    assertThat(target.paths()).containsExactly("/weather");
    assertThat(target.queryKeys()).containsExactly("q");
    assertThatThrownBy(() -> http.targets().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

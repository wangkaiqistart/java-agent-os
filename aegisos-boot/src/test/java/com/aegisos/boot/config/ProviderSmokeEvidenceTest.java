package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.provider.ProviderSmokeFixture;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 仅预检专用 YAML；合成凭据不会进入任何连接，也不允许条件缺失时跳过成功。 */
class ProviderSmokeEvidenceTest {
  @TempDir Path dir;

  @Test
  void missingAndUnsafeConditionsAreNotRunAndNeverRevealValues() throws Exception {
    Path valid = dir.resolve("smoke.yml");
    try (var in = getClass().getResourceAsStream("/application-provider-smoke.yml")) {
      Files.copy(in, valid);
    }
    var env = new HashMap<String, String>();
    env.put("AEGISOS_SMOKE_CONFIG", valid.toString());
    env.put("DEEPSEEK_API_KEY", "SYNTHETIC_SENTINEL");
    var config = ProviderSmokeFixture.preflight(true, env);
    assertThat(config.profileName()).isEqualTo("course-smoke");
    assertThat(config.toString()).doesNotContain("SYNTHETIC_SENTINEL");
    assertSafe(() -> ProviderSmokeFixture.preflight(false, env));
    assertSafe(() -> ProviderSmokeFixture.preflight(true, Map.of()));
    for (String key : List.of("AEGISOS_SMOKE_CONFIG", "DEEPSEEK_API_KEY")) {
      var missing = new HashMap<>(env);
      missing.remove(key);
      assertSafe(() -> ProviderSmokeFixture.preflight(true, missing));
    }
    String text = Files.readString(valid);
    for (String altered :
        List.of(
            text.replace("max-retries: 0", "max-retries: 2"),
            text.replace("deepseek-flash", "unknown"),
            text.replace("pdf16-smoke-001", ""),
            text + "unknown: SYNTHETIC_SENTINEL\n",
            "bad: [SYNTHETIC_SENTINEL")) {
      Files.writeString(valid, altered);
      assertSafe(() -> ProviderSmokeFixture.preflight(true, env));
    }
  }

  private static void assertSafe(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ProviderSmokeFixture.NotRun.class)
        .hasMessage("NOT_RUN:CONDITIONS_MISSING")
        .hasNoCause();
  }
}

package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 只展开 Provider 三字段；其他元数据中的占位符必须保留字面文本。 */
class ProfileEnvironmentTest {
  @TempDir Path dir;

  @Test
  void onlyWholeProviderValuesExpandOnce() throws Exception {
    CourseAlignmentFixtures.agent(
        dir,
        "one",
        "name: a\nidentity: {prompt: '${PRIVATE_SENTINEL}'}\nprovider: {name: '${P}', model: '${M}', temperature: '${T}'}\n");
    var requested = new ArrayList<String>();
    Map<String, String> env = Map.of("P", "p", "M", "m", "T", "0.5");
    var report =
        new ProfileLoader(
                dir,
                ProfileLoaderTest.providers(),
                n -> {
                  requested.add(n);
                  return env.get(n);
                })
            .load();
    assertThat(report.profiles()).hasSize(1);
    assertThat(report.profiles().getFirst().provider().temperature()).isEqualTo(0.5);
    assertThat(report.profiles().getFirst().identity().prompt()).isEqualTo("${PRIVATE_SENTINEL}");
    assertThat(requested).containsExactly("P", "M", "T");
  }

  @Test
  void missingEmptyDefaultEmbeddedRecursiveAndInvalidValuesRejectSafely() throws Exception {
    for (String v : Arrays.asList(null, "", "${P}", "not-a-number", "NaN", "Infinity", "2.1")) {
      Path root = Files.createTempDirectory(dir, "case-");
      CourseAlignmentFixtures.agent(
          root, "one", "name: a\nprovider: {name: p, model: m, temperature: '${T}'}\n");
      var report = new ProfileLoader(root, ProfileLoaderTest.providers(), n -> v).load();
      assertThat(report.profiles()).isEmpty();
      assertThat(report.diagnostics().toString()).doesNotContain("not-a-number", "Infinity");
    }
    for (String value : List.of("x${P}", "${P:default}", "${P:-default}"))
      assertThatThrownBy(() -> new ProfileEnvironmentResolver(n -> "p").resolve(value))
          .hasMessage("INVALID_FIELD");
  }
}

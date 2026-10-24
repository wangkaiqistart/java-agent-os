package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeSecretSnapshotTest {
  @Test
  void sameResolvedValueRemainsStableUntilRestart() {
    var env = new HashMap<String, String>();
    env.put("MODEL_KEY", "synthetic-startup-secret-1234");
    var reads = new AtomicInteger();
    var resolver =
        new EnvironmentCredentialResolver(
            k -> {
              reads.incrementAndGet();
              return env.get(k);
            });
    String first = resolver.resolve("env:MODEL_KEY");
    env.put("MODEL_KEY", "synthetic-changed-secret-5678");
    assertThat(resolver.resolve("env:MODEL_KEY")).isEqualTo(first);
    assertThat(reads).hasValue(1);
    assertThatThrownBy(() -> resolver.snapshot().guard().check(first))
        .hasMessage("SENSITIVE_CONTENT");
    assertThatCode(() -> resolver.snapshot().guard().check("credential-ref: env:MODEL_KEY"))
        .doesNotThrowAnyException();
    assertThat(resolver.toString()).doesNotContain(first);
    assertThat(resolver.snapshot().toString()).doesNotContain(first);
  }

  @Test
  void missingReferenceIsAlsoSnapshotNotReread() {
    var env = new HashMap<String, String>();
    var r = new EnvironmentCredentialResolver(env::get);
    assertThat(r.resolve("env:MISSING")).isNull();
    env.put("MISSING", "later-value");
    assertThat(r.resolve("env:MISSING")).isNull();
  }
}

package com.aegisos.core.memory;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionIdentityTest {
  @Test
  void identityIsExactBoundedAndDoesNotJoinWithSeparators() {
    for (String bad : new String[] {"", " user", "user ", "用户", "x".repeat(65), "../escape"})
      assertThatThrownBy(() -> new SessionIdentity("cli", bad, "p"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_SESSION_IDENTITY");
    assertThat(new SessionIdentity("a:b", "c", "p"))
        .isNotEqualTo(new SessionIdentity("a", "b:c", "p"));
    assertThat(new SessionIdentity("cli", "User", "p"))
        .isNotEqualTo(new SessionIdentity("cli", "user", "p"));
  }

  @Test
  void diagnosticsNeverContainRawIdentity() {
    assertThat(new SessionIdentity("cli", "synthetic-sensitive-identity", "p").toString())
        .doesNotContain("synthetic-sensitive-identity");
  }
}

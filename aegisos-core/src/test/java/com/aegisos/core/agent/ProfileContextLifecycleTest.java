package com.aegisos.core.agent;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ProfileContextLifecycleTest {
  @Test
  void nestedUseIsRejectedAndBackgroundThreadsDoNotInheritContext() throws Exception {
    assertThat(ProfileContext.current()).isNull();
    try (var scope = ProfileContext.enter("first");
        var executor = Executors.newSingleThreadExecutor()) {
      assertThatThrownBy(() -> ProfileContext.enter("nested")).hasMessage("INVALID_INPUT");
      assertThat(ProfileContext.current()).isEqualTo("first");
      assertThat(executor.submit(ProfileContext::current).get(2, TimeUnit.SECONDS)).isNull();
    }
    assertThat(ProfileContext.current()).isNull();
    for (String profile : java.util.List.of("first", "second")) {
      assertThatThrownBy(
              () -> {
                try (var scope = ProfileContext.enter(profile)) {
                  throw new AgentException(AgentErrorCode.AUDIT_UNAVAILABLE);
                }
              })
          .hasMessage("AUDIT_UNAVAILABLE");
      assertThat(ProfileContext.current()).isNull();
    }
  }
}

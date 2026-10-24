package com.aegisos.memory;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class LongTermContextTest {
  @Test
  void oversizedSafeMemoryIsExplicitlyReportedButSensitiveMemoryAlwaysStops() {
    var budget = new ContextBudget(4096, 4096, 4096, 32, 4096, 4096, 8, 1024);
    var guard = new DefaultSensitiveContentGuard(List.of("synthetic-longterm-key-123"));
    var store =
        (SessionStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionStore.class},
                (p, m, a) -> {
                  throw new AssertionError("NO_SESSION_ACCESS");
                });
    var query = new MemoryQuery(UUID.randomUUID().toString(), "p", 1024);
    var memory = new DefaultMemoryService(store, q -> Optional.of("中".repeat(1000)), guard, budget);
    assertThatThrownBy(() -> memory.recallLongTerm(query))
        .isInstanceOf(LongTermMemoryLimitException.class);
    var unsafe =
        new DefaultMemoryService(
            store,
            q -> Optional.of("synthetic-longterm-key-123" + "中".repeat(1000)),
            guard,
            budget);
    assertThatThrownBy(() -> unsafe.recallLongTerm(query)).hasMessage("SENSITIVE_CONTENT");
  }
}

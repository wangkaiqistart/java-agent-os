package com.aegisos.memory;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class MemoryServiceTest {
  final ContextBudget budget = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);
  final SensitiveContentGuard guard =
      new DefaultSensitiveContentGuard(List.of("synthetic-memory-secret-123"));

  @Test
  void unsafeInputIsRejectedBeforeAnyBackendWrite() {
    var writes = new AtomicInteger();
    var store =
        (SessionStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionStore.class},
                (p, m, a) -> {
                  writes.incrementAndGet();
                  throw new AssertionError("NO_STORAGE_EXPECTED");
                });
    var memory = new DefaultMemoryService(store, new EmptyLongTermMemoryStore(), guard, budget);
    assertThatThrownBy(
            () ->
                memory.beginProcessing(
                    UUID.randomUUID().toString(),
                    "p",
                    "synthetic-memory-secret-123",
                    UUID.randomUUID()))
        .hasMessage("SENSITIVE_CONTENT");
    assertThat(writes).hasValue(0);
  }

  @Test
  void defaultLongTermIsEmptyAndSensitiveRecallCannotBeDroppedAndContinued() {
    var store =
        (SessionStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionStore.class},
                (p, m, a) -> {
                  throw new AssertionError("NO_STORAGE_EXPECTED");
                });
    var query = new MemoryQuery(UUID.randomUUID().toString(), "p", 4096);
    assertThat(
            new DefaultMemoryService(store, new EmptyLongTermMemoryStore(), guard, budget)
                .recallLongTerm(query))
        .isEmpty();
    var sensitive =
        new DefaultMemoryService(
            store, q -> Optional.of("synthetic-memory-secret-123"), guard, budget);
    assertThatThrownBy(() -> sensitive.recallLongTerm(query)).hasMessage("SENSITIVE_CONTENT");
  }

  @Test
  void failedTerminalCommitIsNeverAcknowledged() {
    var store =
        (SessionStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {SessionStore.class},
                (p, m, a) -> {
                  throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
                });
    var memory = new DefaultMemoryService(store, new EmptyLongTermMemoryStore(), guard, budget);
    var h =
        new ProcessingHandle(
            UUID.randomUUID(), UUID.randomUUID().toString(), "p", UUID.randomUUID(), 0);
    assertThatThrownBy(() -> memory.finish(h, ProcessingStatus.COMPLETED, null, "safe"))
        .hasMessage("PERSISTENCE_UNCONFIRMED");
  }
}

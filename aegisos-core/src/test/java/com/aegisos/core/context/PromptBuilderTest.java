package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PromptBuilderTest extends MinimalPromptTest {
  @Test
  void optionalMemoryHasSeparateSystemMessageAndFullHistoryIsTrimmedWithoutChangingSource()
      throws Exception {
    Path dir = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(dir.resolve("AGENT.md"), "agent");
    var b = new ContextBudget(4096, 3000, 3000, 32, 2048, 3000, 8, 1024);
    var history = new ArrayList<List<ExchangeGroup>>();
    for (int i = 0; i < 20; i++)
      history.add(List.of(user(UUID.randomUUID(), "old" + i + "中".repeat(100))));
    var current = user(processId, "current");
    var m =
        (MemoryService)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {MemoryService.class},
                (p, method, args) ->
                    switch (method.getName()) {
                      case "loadContextHistory" -> new ContextHistory(history, List.of(current));
                      case "recallLongTerm" -> Optional.of("memory" + "中".repeat(200));
                      default -> throw new AssertionError(method.getName());
                    });
    var source =
        new AgentSourceRegistry(
            List.of(
                new AgentSource(
                    "logical", root.toRealPath(), dir.toRealPath(), root.relativize(dir))));
    var guard = new DefaultSensitiveContentGuard(List.of());
    ToolRegistryView tools =
        new ToolRegistryView() {
          public Optional<AegisTool> find(String n) {
            return Optional.empty();
          }

          public List<ToolSchema> schemas() {
            return List.of();
          }
        };
    var builder =
        new PromptBuilder(
            source,
            new ContextLoader(b, guard),
            m,
            tools,
            b,
            guard,
            Clock.systemUTC(),
            ZoneOffset.UTC);
    if (!supported()) {
      assertThatThrownBy(() -> builder.build(profile(), handle, 20))
          .hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return;
    }
    var built = builder.build(profile(), handle, 20);
    assertThat(built.trimmedProcessings()).isGreaterThan(0);
    assertThat(built.prompt().messages())
        .noneSatisfy(message -> assertThat(message.content()).startsWith("memory"));
    assertThat(built.prompt().messages().getLast().content()).isEqualTo("current");
    assertThat(ContextSizer.bytes(built.prompt())).isLessThanOrEqualTo(4096);
    assertThat(history).hasSize(20);
    history.clear();
    built = builder.build(profile(), handle, 20);
    assertThat(built.prompt().messages().get(1).role()).isEqualTo(ModelMessage.Role.SYSTEM);
    assertThat(built.prompt().messages().get(1).content()).contains("memory");
    assertThat(built.prompt().messages().getFirst().content()).contains("时区");
  }

  ExchangeGroup user(UUID process, String content) {
    return new ExchangeGroup(
        UUID.randomUUID(),
        process,
        0,
        ExchangeGroup.Kind.USER,
        ExchangeGroup.State.CLOSED,
        null,
        0,
        0,
        List.of(new ModelMessage(ModelMessage.Role.USER, content, null, List.of())));
  }
}

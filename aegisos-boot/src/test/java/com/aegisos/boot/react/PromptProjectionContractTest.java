package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.ToolCallProjection;
import com.aegisos.core.memory.ExchangeGroup;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.OpenAiMessageMapper;
import java.util.*;
import org.junit.jupiter.api.Test;

class PromptProjectionContractTest {
  static ExchangeGroup group(UUID process, int ordinal) {
    return new ExchangeGroup(
        UUID.randomUUID(),
        process,
        ordinal,
        ExchangeGroup.Kind.TOOL_EXCHANGE,
        ExchangeGroup.State.CLOSED,
        ordinal,
        1,
        1,
        List.of(
            new ModelMessage(
                ModelMessage.Role.ASSISTANT,
                "intent",
                null,
                List.of(new ToolCall("repeated", "http_get", "{\"url\":\"https://example.org\"}"))),
            new ModelMessage(ModelMessage.Role.TOOL, "safe", "repeated", List.of())));
  }

  @Test
  void repeatedRawIdsAcrossGroupsProjectStableUniqueWireIdsWithoutChangingStorage() {
    var first = group(UUID.randomUUID(), 1);
    var second = group(first.processId(), 2);
    var third = group(UUID.randomUUID(), 1);
    var all = new ArrayList<ModelMessage>();
    for (var g : List.of(first, second, third)) {
      var projected = ToolCallProjection.project(g);
      assertThat(projected).isEqualTo(ToolCallProjection.project(g));
      String id = projected.get(0).toolCalls().getFirst().id();
      assertThat(id).matches("t_[0-9a-f]{32}_[1-9][0-9]*_1");
      assertThat(projected.get(1).toolCallId()).isEqualTo(id);
      all.addAll(projected);
      assertThat(g.messages().getFirst().toolCalls().getFirst().id()).isEqualTo("repeated");
    }
    assertThat(
            all.stream()
                .filter(m -> !m.toolCalls().isEmpty())
                .map(m -> m.toolCalls().getFirst().id()))
        .doesNotHaveDuplicates();
    OpenAiMessageMapper.validate(new ModelRequest("p", "m", all, List.of(), null));
  }
}

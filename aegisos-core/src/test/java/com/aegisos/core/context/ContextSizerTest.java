package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextSizerTest {
  @Test
  void exactCanonicalFormatCountsEscapingAndUtf8() {
    var m = new ModelMessage(ModelMessage.Role.USER, "中\n\"\\", null, List.of());
    var p = new ProfilePrompt(List.of(m), List.of(), null);
    assertThat(ContextSizer.canonical(p))
        .isEqualTo(
            "{\"messages\":[{\"role\":\"USER\",\"content\":\"中\\u000a\\\"\\\\\",\"toolCallId\":null,\"toolCalls\":[]}],\"toolSchemas\":[],\"maxOutputTokens\":null}");
    assertThat(ContextSizer.bytes(p))
        .isEqualTo(ContextSizer.canonical(p).getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(ContextSizer.canonical(p).length());
  }

  @Test
  void schemasArgumentsAndNullAreNotFree() {
    var p =
        new ProfilePrompt(
            List.of(
                new ModelMessage(
                    ModelMessage.Role.ASSISTANT,
                    null,
                    null,
                    List.of(new ToolCall("id", "t", "{\"x\":1}")))),
            List.of(new ToolSchema("t", "description", "{}")),
            32);
    assertThat(ContextSizer.canonical(p))
        .contains(
            "\"toolCallId\":null", "\"argumentsJson\":\"{\\\"x\\\":1}\"", "\"maxOutputTokens\":32");
    assertThat(ContextSizer.messageBytes(p.messages().getFirst())).isPositive();
  }

  @Test
  void mandatoryBudgetIsEnforced() {
    var b = new ContextBudget(4096, 2048, 2048, 32, 8192, 2048, 8, 1024);
    assertThatThrownBy(() -> b.check(ProfilePrompt.text("中".repeat(1000))))
        .hasMessage("CONTEXT_LIMIT");
    assertThatCode(() -> b.check(ProfilePrompt.text("ok"))).doesNotThrowAnyException();
  }
}

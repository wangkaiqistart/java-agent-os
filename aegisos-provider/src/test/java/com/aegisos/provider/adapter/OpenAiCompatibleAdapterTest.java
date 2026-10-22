package com.aegisos.provider.adapter;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleAdapterTest {
  /** 多个意图按原顺序返回；描述符误调用也硬拒绝，无第二轮 HTTP。 */
  @Test
  void multipleIntentsPreserveOrderAndDescriptorCannotExecute() throws Exception {
    String calls =
        "\"tool_calls\":[{\"id\":\"one\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}},{\"id\":\"two\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"n\\\":2}\"}}]";
    for (String text : List.of("", "alongside"))
      try (var stub =
          HttpStub.fixed(
              200,
              HttpStub.text()
                  .replace("\"content\":\"你好\"", "\"content\":\"" + text + "\"," + calls))) {
        var request =
            new ModelRequest(
                "p",
                "m",
                ModelRequest.text("p", "m", "x").messages(),
                List.of(new ToolSchema("lookup", null, "{\"type\":\"object\"}")),
                null);
        var options =
            (org.springframework.ai.openai.OpenAiChatOptions)
                OpenAiMessageMapper.prompt(request).getOptions();
        assertThatThrownBy(() -> options.getToolCallbacks().getFirst().call("{}"))
            .hasMessage("Tool execution forbidden in Provider");
        var response =
            ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit()).chat(request);
        assertThat(response.content()).isEqualTo(text);
        assertThat(response.toolCalls()).extracting(ToolCall::id).containsExactly("one", "two");
        assertThat(response.toolCalls().get(1).argumentsJson()).isEqualTo("{\"n\":2}");
        assertThat(stub.calls).hasValue(1);
      }
  }

  @Test
  void mapsRolesAndGenerationOptionsWithoutDefaultModel() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var calls = List.of(new ToolCall("call-1", "read_file", "{\"path\":\"a\"}"));
      var messages =
          List.of(
              new ModelMessage(ModelMessage.Role.SYSTEM, "sys", null, List.of()),
              new ModelMessage(ModelMessage.Role.USER, "u", null, List.of()),
              new ModelMessage(ModelMessage.Role.ASSISTANT, "", null, calls),
              new ModelMessage(ModelMessage.Role.TOOL, "result", "call-1", List.of()));
      var request =
          new ModelRequest(
              "p",
              "m",
              messages,
              List.of(
                  new ToolSchema(
                      "read_file",
                      "read",
                      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")),
              new GenerationOptions(0.2, 30));
      ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit()).chat(request);
      var json = JsonMapper.builder().build().readTree(stub.bodies.getFirst());
      assertThat(stub.paths).containsExactly("/v1/chat/completions");
      assertThat(json.path("model").asText()).isEqualTo("m");
      assertThat(json.path("temperature").asDouble()).isEqualTo(0.2);
      assertThat(json.path("max_completion_tokens").asInt()).isEqualTo(30);
      assertThat(json.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("call-1");
      assertThat(json.path("tools").size()).isEqualTo(1);
      assertThat(stub.calls).hasValue(1);
    }
  }

  @Test
  void toolOnlyAndMixedResultsReturnStructuredCallsWithoutExecution() throws Exception {
    for (String content : List.of("", "text"))
      try (var stub =
          new HttpStub(
              (e, n) ->
                  HttpStub.reply(
                      e,
                      200,
                      HttpStub.text()
                          .replace(
                              "\"content\":\"你好\"",
                              "\"content\":\""
                                  + content
                                  + "\",\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"cmd\\\":\\\"echo test\\\"}\"}}]")))) {
        var response =
            ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit())
                .chat(ModelRequest.text("p", "m", "x"));
        assertThat(response.toolCalls())
            .containsExactly(new ToolCall("t1", "shell", "{\"cmd\":\"echo test\"}"));
        assertThat(response.content()).isEqualTo(content);
        assertThat(stub.calls).hasValue(1);
      }
  }

  @Test
  void nullableUsageFieldsAndLargeCountsArePreserved() throws Exception {
    for (String usage :
        List.of(
            "{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}",
            "{\"prompt_tokens\":3000000000}"))
      try (var stub =
          new HttpStub(
              (e, n) ->
                  HttpStub.reply(
                      e,
                      200,
                      HttpStub.text()
                          .replace("\"choices\":", "\"usage\":" + usage + ",\"choices\":")))) {
        var response =
            ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit())
                .chat(ModelRequest.text("p", "m", "x"));
        assertThat(response.usage()).isNotNull();
        if (usage.contains("3000000000")) {
          assertThat(response.usage().inputTokens()).isEqualTo(3000000000L);
          assertThat(response.usage().totalTokens()).isNull();
        } else assertThat(response.usage().totalTokens()).isEqualTo(6);
      }
  }

  @Test
  void invalidSchemaIsRejectedBeforeSending() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var request =
          new ModelRequest(
              "p",
              "m",
              ModelRequest.text("p", "m", "x").messages(),
              List.of(new ToolSchema("x", "", "{\"type\":\"array\"}")),
              null);
      assertThatThrownBy(
              () -> ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit()).chat(request))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e ->
                  assertThat(e.failure().code())
                      .isEqualTo(ProviderErrorCode.UNSUPPORTED_TOOL_SCHEMA));
      assertThat(stub.calls).hasValue(0);
    }
  }

  @Test
  void emptyResponseIsProtocolError() throws Exception {
    try (var stub =
        new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text().replace("你好", "")))) {
      assertThatThrownBy(
              () ->
                  ProviderFixtures.service(stub, 0, new ProviderFixtures.Audit())
                      .chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e ->
                  assertThat(e.failure().code())
                      .isEqualTo(ProviderErrorCode.PROVIDER_PROTOCOL_ERROR));
      assertThat(stub.calls).hasValue(1);
    }
  }
}

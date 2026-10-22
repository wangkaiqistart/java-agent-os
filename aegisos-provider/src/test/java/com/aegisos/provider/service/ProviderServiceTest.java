package com.aegisos.provider.service;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderServiceTest {
  /** 嵌套不支持字段必须作为本地拒绝，不能记成已发送的失败尝试。 */
  @Test
  void nestedUnsupportedSchemaUsesRejectionBoundary() throws Exception {
    try (var stub =
        new com.aegisos.provider.support.HttpStub(
            (e, n) ->
                com.aegisos.provider.support.HttpStub.reply(
                    e, 200, com.aegisos.provider.support.HttpStub.text()))) {
      var audit = new com.aegisos.provider.support.ProviderFixtures.Audit();
      var request =
          new ModelRequest(
              "p",
              "m",
              ModelRequest.text("p", "m", "x").messages(),
              List.of(
                  new ToolSchema(
                      "t",
                      null,
                      "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"format\":\"secret-sentinel\"}}}")),
              null);
      assertThatThrownBy(
              () ->
                  com.aegisos.provider.support.ProviderFixtures.service(stub, 0, audit)
                      .chat("S", request))
          .hasMessage("UNSUPPORTED_TOOL_SCHEMA")
          .hasNoCause();
      assertThat(stub.calls).hasValue(0);
      assertThat(audit.starts).isEmpty();
      assertThat(audit.rejected).hasSize(1);
      assertThat(audit.rejected.getFirst().sessionId()).isEqualTo("S");
    }
  }

  @Test
  void responseHasChosenIdentityUniqueRequestIdsAndUnknownUsage() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var audit = new ProviderFixtures.Audit();
      var service = ProviderFixtures.service(stub, 0, audit);
      var a = service.chat(ModelRequest.text("p", "m", "hello"));
      var b = service.chat(ModelRequest.text("p", "m", "hello"));
      assertThat(a.requestId()).isNotEqualTo(b.requestId());
      assertThat(a.providerName()).isEqualTo("p");
      assertThat(a.modelName()).isEqualTo("m");
      assertThat(a.content()).isEqualTo("你好");
      assertThat(a.usage()).isNull();
      assertThat(stub.calls).hasValue(2);
      assertThat(audit.starts).hasSize(2);
      assertThat(audit.completed).hasSize(2);
      assertThat(audit.starts.getFirst().requestId()).isEqualTo(a.requestId());
      assertThat(audit.completed.getFirst().durationMs()).isNotNegative();
    }
  }

  @Test
  void beginAndCompletionBracketAdapter() {
    var events = new ArrayList<String>();
    var audit =
        new ProviderFixtures.Audit() {
          @Override
          public com.aegisos.core.audit.AuditHandle begin(com.aegisos.core.audit.AuditStart s) {
            events.add("begin");
            return super.begin(s);
          }

          @Override
          public void complete(
              com.aegisos.core.audit.AuditHandle h, com.aegisos.core.audit.AuditCompletion c) {
            events.add("complete");
            super.complete(h, c);
          }
        };
    var d =
        new com.aegisos.provider.config.ProviderDefinition(
            "p",
            "openai-compatible",
            Set.of("m"),
            java.net.URI.create("https://example.org"),
            "env:KEY",
            0,
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(1));
    var registration =
        new com.aegisos.provider.config.ProviderRegistration(
            d,
            r -> {
              events.add("model");
              return new ModelResponse(
                  null,
                  "ignored",
                  "ignored",
                  "answer",
                  List.of(),
                  "stop",
                  new TokenUsage(2L, null, null));
            },
            null);
    var response =
        new DefaultProviderService(List.of(registration), audit, () -> {})
            .chat(ModelRequest.text("p", "m", "test"));
    assertThat(events).containsExactly("begin", "model", "complete");
    assertThat(response.usage().inputTokens()).isEqualTo(2L);
    assertThat(response.usage().totalTokens()).isNull();
  }
}

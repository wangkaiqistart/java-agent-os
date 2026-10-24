package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SensitiveOutputBoundaryTest {
  /** 必须在接受模型响应的事务之前拒绝，后续工具参数形状校验不能补救已入库的秘密。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "synthetic-external-carrier-value",
        "null hunter2",
        "env:REFERENCE actual-value",
        "${TOKEN} actual-value",
        "\"actual-value"
      })
  void escapedSensitiveFieldNeverPersistsModelIntent(String sensitive) throws Exception {
    try (var f = new ToolLoopFixture();
        var r = f.base.openRuntime()) {
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      var root =
          (tools.jackson.databind.node.ObjectNode)
              json.readTree(f.response(f.tool("id", "/weather")));
      var function =
          (tools.jackson.databind.node.ObjectNode)
              root.path("choices")
                  .get(0)
                  .path("message")
                  .path("tool_calls")
                  .get(0)
                  .path("function");
      String scalar = json.writeValueAsString(sensitive);
      function.put("arguments", "{\"api\\u005fkey\":" + scalar + "}");
      String payload = json.writeValueAsString(root);
      f.responses.set(n -> payload);
      String session = r.agents().createSession("p").sessionId();
      var result = r.agents().process(new AgentRequest(session, "p", "safe"));
      assertThat(result.errorCode())
          .isEqualTo(
              f.base.secureReadsSupported()
                  ? AgentErrorCode.SENSITIVE_CONTENT
                  : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      assertThat(f.gets).hasValue(0);
      assertThat(result.reply()).isNull();
      assertThat(result.toString()).doesNotContain(sensitive);
      assertThat(r.memory().loadSession(session, "p").messages())
          .extracting(m -> m.content())
          .containsExactly("safe");
      // 同时检查实际存储列：只看 DTO.toString() 的脱敏输出不足以证明未入库。
      try (var connection = f.base.reopen();
          var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("SELECT count(*) FROM session_messages")) {
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
        try (var rows = statement.executeQuery("SELECT count(*) FROM tool_invocations")) {
          assertThat(rows.getInt(1)).isZero();
        }
      }
    }
  }

  @Test
  void escapedSensitiveToolArgumentNeverReachesToolOrMessageHistory() throws Exception {
    try (var f = new ToolLoopFixture()) {
      String secret = "synthetic-runtime-credential-123";
      String encoded =
          secret
              .chars()
              .mapToObj(c -> String.format("\\u%04x", c))
              .collect(java.util.stream.Collectors.joining());
      f.responses.set(n -> f.response(f.tool("id", "/weather")).replace("/weather", "/weather"));
      try (var r = f.base.openRuntime()) {
        String response = f.response(f.tool("id", "/weather"));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(response);
        var function =
            (tools.jackson.databind.node.ObjectNode)
                root.path("choices")
                    .get(0)
                    .path("message")
                    .path("tool_calls")
                    .get(0)
                    .path("function");
        function.put("arguments", "{\"url\":\"" + encoded + "\"}");
        String payload = json.writeValueAsString(root);
        f.responses.set(n -> payload);
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        assertThat(result.errorCode())
            .isEqualTo(
                f.base.secureReadsSupported()
                    ? AgentErrorCode.SENSITIVE_CONTENT
                    : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
        assertThat(result.toString()).doesNotContain(secret);
        assertThat(result.reply()).isNull();
        assertThat(f.gets).hasValue(0);
        assertThat(r.memory().loadSession(session, "p").messages())
            .extracting(m -> m.content())
            .containsExactly("safe");
      }
    }
  }
}

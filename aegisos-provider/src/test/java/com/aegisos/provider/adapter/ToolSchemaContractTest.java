package com.aegisos.provider.adapter;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** 比较实际 HTTP JSON 语义树，而不只查看 Spring AI 中间对象。 */
class ToolSchemaContractTest {
  static final String FULL =
      """
 {"type":"object","description":"根说明","properties":{
 "nested":{"type":"object","description":"嵌套","properties":{"mode":{"type":"string","description":"模式","enum":["a","b"]}},"required":["mode"],"additionalProperties":false},
 "items":{"type":"array","description":"列表","items":{"type":"object","properties":{"value":{"type":"number","enum":[1,2.5]},"flag":{"type":"boolean","enum":[true,false]},"empty":{"type":"null","enum":[null]}}}},
 "count":{"type":"integer","enum":[1,2]}},"required":["nested"],"additionalProperties":true}
 """;

  static ModelRequest request(String schema) {
    return new ModelRequest(
        "p",
        "m",
        ModelRequest.text("p", "m", "x").messages(),
        List.of(new ToolSchema("lookup", "完整说明", schema)),
        null);
  }

  @Test
  void finalJsonRetainsEverySupportedFieldAndDoesNotInventDefaults() throws Exception {
    for (String schema :
        List.of(FULL, "{\"type\":\"object\",\"properties\":{\"optional\":{\"type\":\"string\"}}}"))
      try (var stub = HttpStub.fixed(200, HttpStub.text())) {
        var audit = new ProviderFixtures.Audit();
        ProviderFixtures.service(stub, 0, audit).chat(request(schema));
        var mapper = JsonMapper.builder().build();
        var function =
            mapper.readTree(stub.bodies.getFirst()).path("tools").get(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("lookup");
        assertThat(function.path("description").asText()).isEqualTo("完整说明");
        assertThat(function.path("parameters")).isEqualTo(mapper.readTree(schema));
        assertThat(function.path("strict").asBoolean()).isFalse();
        assertThat(stub.calls).hasValue(1);
        assertThat(audit.completed).hasSize(1);
      }
  }

  static Stream<String> invalid() {
    return Stream.of(
        "{\"type\":\"object\",\"type\":\"object\"}",
        "[]",
        "{\"type\":\"array\"}",
        "{\"type\":[\"object\"]}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"secret-sentinel\"}}}",
        "{\"type\":\"object\",\"required\":[\"absent\"]}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\",\"x\"]}",
        "{\"type\":\"object\",\"additionalProperties\":{}}",
        "{\"type\":\"object\",\"items\":{\"type\":\"string\"}}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"array\",\"items\":[]}}}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"enum\":[]}}}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"enum\":[\"a\",\"a\"]}}}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\",\"enum\":[\"a\"]}}}",
        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\".*\"}}}",
        "{\"type\":\"object\",\"$defs\":{}}",
        "{\"type\":\"object\",\"description\":true}",
        "{\"type\":\"object\",\"unknown\":\"secret-sentinel\"}",
        "{\"type\":\"object\",\"description\":\"" + "x".repeat(262144) + "\"}",
        "{\"type\":\"object\",\"properties\":{\"x\":"
            + "{\"type\":\"array\",\"items\":".repeat(33)
            + "{\"type\":\"string\"}"
            + "}".repeat(33)
            + "}}");
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void unsupportedSchemasRejectBeforeAuditBeginAndHttp(String schema) throws Exception {
    try (var stub = HttpStub.fixed(200, HttpStub.text())) {
      var audit = new ProviderFixtures.Audit();
      assertThatThrownBy(
              () -> ProviderFixtures.service(stub, 0, audit).chat("Session", request(schema)))
          .hasMessage("UNSUPPORTED_TOOL_SCHEMA")
          .hasNoCause();
      assertThat(stub.calls).hasValue(0);
      assertThat(audit.starts).isEmpty();
      assertThat(audit.rejected).hasSize(1);
      assertThat(audit.rejected.getFirst().sessionId()).isEqualTo("Session");
      assertThat(audit.rejected.getFirst().toString()).doesNotContain("secret-sentinel");
    }
  }
}

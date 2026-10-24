package com.aegisos.tool;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpGetSchemaContractTest {
  @Test
  void schemaMatchesSupportedSubsetExactly() {
    var schema = new HttpGetTool().schema();
    assertThat(schema.name()).isEqualTo("http_get");
    assertThat(schema.description()).isNotBlank();
    var node = JsonMapper.builder().build().readTree(schema.inputSchemaJson());
    assertThat(node.propertyNames())
        .containsExactlyInAnyOrder("type", "properties", "required", "additionalProperties");
    assertThat(node.path("type").asText()).isEqualTo("object");
    assertThat(node.path("properties").propertyNames()).containsExactly("url");
    assertThat(node.path("properties").path("url").propertyNames())
        .containsExactlyInAnyOrder("type", "description");
    assertThat(node.path("properties").path("url").path("type").asText()).isEqualTo("string");
    assertThat(node.path("required").size()).isEqualTo(1);
    assertThat(node.path("required").get(0).asText()).isEqualTo("url");
    assertThat(node.path("additionalProperties").asBoolean()).isFalse();
    var registry = new ToolRegistry(java.util.List.of(new HttpGetTool()));
    assertThat(registry.schemas()).hasSize(1);
    assertThatThrownBy(
            () -> new ToolRegistry(java.util.List.of(new HttpGetTool(), new HttpGetTool())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

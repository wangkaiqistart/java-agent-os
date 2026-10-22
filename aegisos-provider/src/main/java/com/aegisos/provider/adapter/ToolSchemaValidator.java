package com.aegisos.provider.adapter;

import com.aegisos.core.provider.*;
import com.aegisos.provider.service.ProviderErrorClassifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 仅支持公开契约中的 JSON Schema 子集。递归拒绝不支持的语义，不能通过删字段实现“兼容”。 校验只读取内存文本，错误仅为固定代码，原始 schema 和解析器异常不会跨越边界。 */
public final class ToolSchemaValidator {
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(262144)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ToolSchemaValidator() {}

  public static void validate(String text) {
    try {
      if (text == null
          || text.length() > 262144
          || text.getBytes(StandardCharsets.UTF_8).length > 262144) throw invalid();
      JsonNode root = JSON.readTree(text);
      if (root == null || !root.isObject() || !"object".equals(root.path("type").asText()))
        throw invalid();
      count(root, 0, new int[] {0});
      schema(root);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  /** 节点计数包含属性名与值；JSON 解析器另有深度/重复键限制，避免构造巨树后才拒绝。 */
  private static void count(JsonNode n, int depth, int[] count) {
    if (depth > 32 || ++count[0] > 4096) throw invalid();
    if (n.isObject())
      for (String name : n.propertyNames()) {
        if (++count[0] > 4096) throw invalid();
        count(n.get(name), depth + 1, count);
      }
    else if (n.isArray()) for (JsonNode child : n) count(child, depth + 1, count);
  }

  private static void schema(JsonNode n) {
    if (!n.isObject() || !n.path("type").isString()) throw invalid();
    String type = n.path("type").asText();
    if (!Set.of("object", "array", "string", "number", "integer", "boolean", "null").contains(type))
      throw invalid();
    var allowed = new HashSet<>(Set.of("type", "description"));
    if (type.equals("object"))
      allowed.addAll(Set.of("properties", "required", "additionalProperties"));
    else if (type.equals("array")) allowed.add("items");
    else allowed.add("enum");
    for (String key : n.propertyNames()) if (!allowed.contains(key)) throw invalid();
    if (n.has("description") && !n.get("description").isString()) throw invalid();
    if (type.equals("object")) {
      JsonNode properties = n.get("properties");
      if (properties != null) {
        if (!properties.isObject()) throw invalid();
        for (JsonNode child : properties) schema(child);
      }
      if (n.has("required")) {
        JsonNode required = n.get("required");
        if (!required.isArray()) throw invalid();
        var unique = new HashSet<String>();
        for (JsonNode item : required)
          if (!item.isString()
              || !unique.add(item.asText())
              || properties == null
              || !properties.has(item.asText())) throw invalid();
      }
      if (n.has("additionalProperties") && !n.get("additionalProperties").isBoolean())
        throw invalid();
    } else if (type.equals("array")) {
      if (!n.has("items")) throw invalid();
      schema(n.get("items"));
    } else if (n.has("enum")) {
      JsonNode values = n.get("enum");
      if (!values.isArray() || values.isEmpty()) throw invalid();
      var unique = new HashSet<String>();
      for (JsonNode value : values) {
        boolean compatible =
            switch (type) {
              case "string" -> value.isString();
              case "boolean" -> value.isBoolean();
              case "null" -> value.isNull();
              case "number" -> value.isNumber() && Double.isFinite(value.doubleValue());
              case "integer" ->
                  value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
              default -> false;
            };
        if (!compatible) throw invalid();
        String canonical =
            value.isNumber()
                ? "number:" + value.decimalValue().stripTrailingZeros().toString()
                : value.toString();
        if (!unique.add(canonical)) throw invalid();
      }
    }
  }

  private static ProviderException invalid() {
    return ProviderErrorClassifier.error(
        ProviderErrorCode.UNSUPPORTED_TOOL_SCHEMA, false, ExternalOutcome.NOT_SENT);
  }
}

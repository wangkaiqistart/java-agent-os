package com.aegisos.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import com.aegisos.core.tool.ValidatedArguments;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 严格且有界的参数解码；指纹只用于相等判定，不是参数加密。 */
public final class ToolArgumentsParser {
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(8192)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final ContextBudget budget;
  private final SensitiveContentGuard guard;

  public ToolArgumentsParser(ContextBudget budget, SensitiveContentGuard guard) {
    this.budget = budget;
    this.guard = guard;
  }

  public ValidatedArguments parse(String name, String text) {
    guard.check(name);
    guard.checkJson(text);
    if (text == null || ContextSizer.utf8(text) > budget.maxToolArgumentsBytes()) throw invalid();
    try {
      JsonNode root = JSON.readTree(text);
      if (root == null || !root.isObject()) throw invalid();
      if ("http_get".equals(name)) {
        var url = root.get("url");
        if (root.size() != 1
            || url == null
            || !url.isString()
            || url.asText().isBlank()
            || ContextSizer.utf8(url.asText()) > 2048) throw invalid();
      }
      var canonical = canonical(root);
      @SuppressWarnings("unchecked")
      Map<String, Object> values = JSON.treeToValue(root, Map.class);
      return new ValidatedArguments(values, canonical, fingerprint(name, canonical));
    } catch (AgentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static String canonical(JsonNode n) {
    if (n.isObject()) {
      var keys = new ArrayList<>(n.propertyNames());
      Collections.sort(keys);
      return keys.stream()
          .map(k -> ContextSizer.quote(k) + ":" + canonical(n.get(k)))
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (n.isArray()) {
      var values = new ArrayList<String>();
      for (var child : n) values.add(canonical(child));
      return String.join(",", values).transform(s -> "[" + s + "]");
    }
    if (n.isString()) return ContextSizer.quote(n.asText());
    if (n.isNumber()) return n.decimalValue().stripTrailingZeros().toPlainString();
    return n.toString();
  }

  public static String fingerprint(String name, String json) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((name + "\n" + json).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("HASH_UNAVAILABLE");
    }
  }

  private static AgentException invalid() {
    return new AgentException(AgentErrorCode.INVALID_ARGUMENTS);
  }
}

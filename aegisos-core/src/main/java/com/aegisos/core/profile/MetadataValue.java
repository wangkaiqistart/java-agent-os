package com.aegisos.core.profile;

import java.util.*;

/** 受限元数据树：深度冻结且拒绝秘密/连接字段；这里只承载值，不赋予执行能力。 */
public record MetadataValue(Object value) {
  public MetadataValue {
    value = freeze(value, 0, new int[] {0});
  }

  public static Map<String, Object> map(Map<String, ?> values) {
    @SuppressWarnings("unchecked")
    Map<String, Object> frozen =
        (Map<String, Object>) new MetadataValue(values == null ? Map.of() : values).value();
    return frozen;
  }

  private static Object freeze(Object v, int depth, int[] nodes) {
    if (depth > 32 || ++nodes[0] > 4096) throw invalid();
    if (v == null || v instanceof String || v instanceof Boolean) return v;
    if (v instanceof Number n) {
      if (!Double.isFinite(n.doubleValue())
          || !(n instanceof Byte
              || n instanceof Short
              || n instanceof Integer
              || n instanceof Long
              || n instanceof Double
              || n instanceof Float
              || n instanceof java.math.BigInteger
              || n instanceof java.math.BigDecimal)) throw invalid();
      return n;
    }
    if (v instanceof Map<?, ?> m) {
      var out = new LinkedHashMap<String, Object>();
      for (var entry : m.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw invalid();
        String normal = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (normal.matches(
            ".*(apikey|token|password|secret|authorization|credential|webhook|url|connectionstring|dsn).*"))
          throw invalid();
        out.put(key, freeze(entry.getValue(), depth + 1, nodes));
      }
      return Collections.unmodifiableMap(out);
    }
    if (v instanceof List<?> list) {
      if (list.size() > 256) throw invalid();
      var out = new ArrayList<Object>();
      for (Object item : list) out.add(freeze(item, depth + 1, nodes));
      return Collections.unmodifiableList(out);
    }
    throw invalid();
  }

  public static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_FIELD");
  }

  @Override
  public String toString() {
    return "MetadataValue[redacted]";
  }
}

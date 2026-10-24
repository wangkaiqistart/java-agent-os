package com.aegisos.boot.react;

import com.aegisos.boot.config.SafeProfileYamlReader;
import com.aegisos.core.context.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.*;

/** 独立受控 YAML，不接受 Spring 导入或任何未知键；不访问环境变量中的凭据值。 */
final class RealReactSmokeConfiguration {
  final Map<String, Object> tree;
  final String runId, fingerprint;
  final Path parent;
  final BigDecimal latitude, longitude;
  final String zone;
  final List<String> messages;
  final int iterations, modelLimit, getLimit;

  private RealReactSmokeConfiguration(Map<String, Object> tree) throws Exception {
    this.tree = tree;
    exact(tree, "run-id", "workspace-parent", "provider", "weather", "messages", "budgets");
    runId = text(tree, "run-id");
    if (!runId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid();
    Path configured = Path.of(text(tree, "workspace-parent"));
    if (!configured.isAbsolute()
        || !Files.isDirectory(configured)
        || Files.isSymbolicLink(configured)) throw invalid();
    parent = configured.toRealPath();
    if (!parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
      throw invalid();
    var provider = map(tree, "provider");
    exact(provider, "name", "model", "base-url", "credential-ref", "thinking-mode");
    if (!"deepseek-main".equals(text(provider, "name"))
        || !"deepseek-flash".equals(text(provider, "model"))
        || !"https://api.deepseek.com".equals(text(provider, "base-url"))
        || !"env:DEEPSEEK_API_KEY".equals(text(provider, "credential-ref"))
        || !"disabled".equals(text(provider, "thinking-mode"))) throw invalid();
    var weather = map(tree, "weather");
    exact(weather, "city", "latitude", "longitude", "zone", "target", "current-fields");
    text(weather, "city");
    latitude = decimal(weather, "latitude");
    longitude = decimal(weather, "longitude");
    if (latitude.abs().compareTo(new BigDecimal("90")) > 0
        || longitude.abs().compareTo(new BigDecimal("180")) > 0) throw invalid();
    zone = text(weather, "zone");
    ZoneId.of(zone);
    if (!List.of("temperature_2m", "weather_code", "wind_speed_10m")
        .equals(weather.get("current-fields"))) throw invalid();
    var target = map(weather, "target");
    exact(target, "scheme", "host", "port", "method", "paths", "query-keys");
    if (!"https".equals(target.get("scheme"))
        || !"api.open-meteo.com".equals(target.get("host"))
        || !Integer.valueOf(443).equals(target.get("port"))
        || !"GET".equals(target.get("method"))
        || !List.of("/v1/forecast").equals(target.get("paths"))
        || !List.of("latitude", "longitude", "current", "timezone")
            .equals(target.get("query-keys"))) throw invalid();
    if (!(tree.get("messages") instanceof List<?> list) || list.size() != 2) throw invalid();
    var texts = new ArrayList<String>();
    for (Object item : (List<?>) tree.get("messages")) {
      if (!(item instanceof String s) || s.isBlank() || ContextSizer.utf8(s) > 32768)
        throw invalid();
      new DefaultSensitiveContentGuard(List.of()).check(s);
      texts.add(s);
    }
    messages = List.copyOf(texts);
    var budget = map(tree, "budgets");
    exact(
        budget,
        "max-iterations-per-processing",
        "provider-max-retries",
        "max-model-requests",
        "max-weather-gets");
    iterations = positive(budget, "max-iterations-per-processing", 3);
    modelLimit = positive(budget, "max-model-requests", 6);
    getLimit = positive(budget, "max-weather-gets", 2);
    if (!Integer.valueOf(0).equals(budget.get("provider-max-retries"))) throw invalid();
    var publicTree = new TreeMap<>(tree);
    publicTree.remove("workspace-parent");
    fingerprint =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical(publicTree).getBytes(StandardCharsets.UTF_8)));
  }

  static RealReactSmokeConfiguration read(Path path) {
    try {
      return new RealReactSmokeConfiguration(new SafeProfileYamlReader().read(path));
    } catch (Exception e) {
      throw invalid();
    }
  }

  static Map<String, Object> map(Map<String, Object> source, String key) {
    if (!(source.get(key) instanceof Map<?, ?> raw)) throw invalid();
    var result = new LinkedHashMap<String, Object>();
    raw.forEach(
        (k, v) -> {
          if (!(k instanceof String)) throw invalid();
          result.put((String) k, v);
        });
    return result;
  }

  private static void exact(Map<String, Object> map, String... keys) {
    if (!map.keySet().equals(Set.of(keys))) throw invalid();
  }

  private static String text(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof String s) || s.isBlank()) throw invalid();
    return s;
  }

  private static BigDecimal decimal(Map<String, Object> map, String key) {
    if (!(map.get(key) instanceof Number n)) throw invalid();
    return new BigDecimal(n.toString());
  }

  private static int positive(Map<String, Object> map, String key, int max) {
    if (!(map.get(key) instanceof Integer n) || n < 1 || n > max) throw invalid();
    return n;
  }

  /** 与 Python verifier 共用的非秘密规范：排序对象键、普通十进制数、固定 JSON 转义。 */
  static String canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((k, v) -> sorted.put((String) k, v));
      return sorted.entrySet().stream()
          .map(e -> ContextSizer.quote(e.getKey()) + ":" + canonical(e.getValue()))
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list)
      return list.stream()
          .map(RealReactSmokeConfiguration::canonical)
          .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    if (value instanceof Number n)
      return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
    if (value instanceof Boolean b) return b.toString();
    if (value == null) return "null";
    return ContextSizer.quote(value.toString());
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_REACT_SMOKE_CONFIGURATION");
  }

  @Override
  public String toString() {
    return "RealReactSmokeConfiguration[redacted]";
  }
}

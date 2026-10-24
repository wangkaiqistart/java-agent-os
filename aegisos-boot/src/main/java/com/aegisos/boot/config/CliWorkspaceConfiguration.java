package com.aegisos.boot.config;

import com.aegisos.core.context.DefaultSensitiveContentGuard;
import java.nio.file.*;
import java.util.*;

/** 唯一批准的全局 YAML 入口。字段白名单阻止 Spring 导入/日志/明文凭据等额外通道。 */
public final class CliWorkspaceConfiguration {
  private static final Map<String, Set<String>> KEYS =
      Map.ofEntries(
          Map.entry("", Set.of("aegisos")),
          Map.entry("aegisos", Set.of("audit", "providers", "react", "sandbox")),
          Map.entry("aegisos.audit", Set.of("path", "busy-timeout-ms")),
          Map.entry(
              "aegisos.providers[]",
              Set.of(
                  "name",
                  "adapter",
                  "models",
                  "base-url",
                  "credential-ref",
                  "thinking-mode",
                  "max-retries",
                  "connect-timeout",
                  "read-timeout")),
          Map.entry(
              "aegisos.react",
              Set.of(
                  "max-context-bytes",
                  "max-message-bytes",
                  "max-context-file-bytes",
                  "max-context-files",
                  "max-tool-arguments-bytes",
                  "max-tool-result-bytes",
                  "max-tools-per-response",
                  "max-output-tokens",
                  "zone")),
          Map.entry("aegisos.sandbox", Set.of("http-get")),
          Map.entry(
              "aegisos.sandbox.http-get",
              Set.of(
                  "dns-timeout",
                  "connect-timeout",
                  "read-timeout",
                  "execution-timeout",
                  "max-response-bytes",
                  "targets")),
          Map.entry(
              "aegisos.sandbox.http-get.targets[]",
              Set.of("scheme", "host", "port", "method", "paths", "query-keys")));

  public Map<String, Object> load(Path workspace) {
    try {
      if (!workspace.isAbsolute()) throw invalid();
      Path root = workspace.toRealPath(),
          directory = root.resolve(".aegisos"),
          file = directory.resolve("config.yml");
      if (Files.isSymbolicLink(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw invalid();
      Map<String, Object> tree = new SafeProfileYamlReader().read(file);
      var properties = new LinkedHashMap<String, Object>();
      flatten(tree, "", "", properties);
      if (!properties.containsKey("aegisos.react.max-context-bytes")) throw invalid();
      String configured =
          Objects.toString(properties.get("aegisos.audit.path"), ".aegisos/aegisos.db");
      Path database = root.resolve(configured).normalize();
      if (!database.startsWith(root) || database.equals(root)) throw invalid();
      for (Path p = database; p != null && p.startsWith(root); p = p.getParent())
        if (Files.isSymbolicLink(p)) throw invalid();
      properties.put("aegisos.audit.path", database.toString());
      properties.put("aegisos.workspace-root", root.toString());
      return Map.copyOf(properties);
    } catch (java.io.IOException | RuntimeException e) {
      throw invalid();
    }
  }

  private static void flatten(Object value, String shape, String path, Map<String, Object> out) {
    if (value instanceof Map<?, ?> map) {
      Set<String> allowed = KEYS.get(shape);
      if (allowed == null) throw invalid();
      for (var e : map.entrySet()) {
        if (!(e.getKey() instanceof String key) || !allowed.contains(key)) throw invalid();
        flatten(
            e.getValue(),
            shape.isEmpty() ? key : shape + "." + key,
            path.isEmpty() ? key : path + "." + key,
            out);
      }
    } else if (value instanceof List<?> list) {
      if (!Set.of(
              "aegisos.providers",
              "aegisos.providers[].models",
              "aegisos.sandbox.http-get.targets",
              "aegisos.sandbox.http-get.targets[].paths",
              "aegisos.sandbox.http-get.targets[].query-keys")
          .contains(shape)) throw invalid();
      for (int i = 0; i < list.size(); i++)
        flatten(list.get(i), shape + "[]", path + "[" + i + "]", out);
    } else {
      if (value == null || value instanceof Double d && !Double.isFinite(d)) throw invalid();
      // 不接受值中的环境占位符；唯一凭据入口是 credential-ref 的 env:NAME。
      String text = value.toString();
      if (text.contains("${") || text.contains("#{")) throw invalid();
      new DefaultSensitiveContentGuard(List.of()).check(text);
      if (shape.endsWith(".credential-ref") && !text.matches("env:[A-Za-z_][A-Za-z0-9_]*"))
        throw invalid();
      if (shape.endsWith(".thinking-mode") && !"disabled".equals(value)) throw invalid();
      out.put(path, value);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_WORKSPACE_CONFIGURATION");
  }
}

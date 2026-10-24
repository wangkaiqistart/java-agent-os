package com.aegisos.tool;

import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.util.*;

/** 精确名称的不可变注册表；工具来源不影响唯一执行入口。 */
public final class ToolRegistry implements ToolRegistryView {
  private final Map<String, AegisTool> entries;

  public ToolRegistry(Collection<AegisTool> tools) {
    var map = new LinkedHashMap<String, AegisTool>();
    for (var t : tools) {
      var s = t.schema();
      if (s == null
          || s.name() == null
          || !s.name().matches("[A-Za-z0-9_-]{1,64}")
          || map.putIfAbsent(s.name(), t) != null)
        throw new IllegalArgumentException("INVALID_TOOL_REGISTRY");
    }
    entries = Collections.unmodifiableMap(map);
  }

  public Optional<AegisTool> find(String n) {
    return Optional.ofNullable(entries.get(n));
  }

  public List<ToolSchema> schemas() {
    return entries.values().stream().map(AegisTool::schema).toList();
  }
}

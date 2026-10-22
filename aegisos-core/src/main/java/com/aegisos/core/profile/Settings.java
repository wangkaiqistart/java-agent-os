package com.aegisos.core.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 迭代/历史上限在本轮仅承载；后续 ReAct 消费前仍需遵守自己的规格。 */
public record Settings(
    Integer maxIterations, Integer maxHistoryTurns, Map<String, Object> extensions) {
  public Settings {
    if ((maxIterations != null && maxIterations <= 0)
        || (maxHistoryTurns != null && maxHistoryTurns <= 0)) throw MetadataValue.invalid();
    extensions = MetadataValue.map(extensions);
  }

  /** 返回只读映射；嵌套值已在构造时递归冻结，保留合法的 null 元数据。 */
  public Map<String, Object> extensions() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
  }

  @Override
  public String toString() {
    return "Settings[redacted]";
  }
}

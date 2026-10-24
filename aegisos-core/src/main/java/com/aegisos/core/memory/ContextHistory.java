package com.aegisos.core.memory;

import java.util.*;

public record ContextHistory(
    List<List<ExchangeGroup>> completedProcessings, List<ExchangeGroup> currentGroups) {
  public ContextHistory {
    completedProcessings = completedProcessings.stream().map(List::copyOf).toList();
    currentGroups = List.copyOf(currentGroups);
  }

  /** 外层以只读视图返回；构造时每个内部列表已经独立 copyOf，不能修改嵌套历史。 */
  public List<List<ExchangeGroup>> completedProcessings() {
    return Collections.unmodifiableList(completedProcessings);
  }

  @Override
  public String toString() {
    return "ContextHistory[redacted]";
  }
}

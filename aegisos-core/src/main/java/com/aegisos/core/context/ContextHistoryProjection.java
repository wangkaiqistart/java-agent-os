package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import java.util.*;

/** 仅构建发送投影：可选记忆→最旧完整历史处理→当前最旧闭合工具组；不修改持久历史。 */
public final class ContextHistoryProjection {
  private ContextHistoryProjection() {}

  public record Selection(
      ProfilePrompt prompt,
      int processings,
      int groups,
      boolean memoryRemoved,
      long bytesRemoved) {}

  public static Selection select(
      ModelMessage system,
      String memory,
      boolean oversizedMemory,
      ContextHistory history,
      List<ToolSchema> schemas,
      ContextBudget budget) {
    var complete = new ArrayList<>(history.completedProcessings());
    var current =
        new ArrayList<>(
            history.currentGroups().stream()
                .filter(g -> g.state() == ExchangeGroup.State.CLOSED)
                .toList());
    var required = new ArrayList<ModelMessage>();
    required.add(system);
    for (var g : current) if (g.kind() == ExchangeGroup.Kind.USER) required.addAll(g.messages());
    budget.check(new ProfilePrompt(required, schemas, budget.maxOutputTokens()));
    int dropped = 0, groups = 0;
    boolean removed = oversizedMemory;
    var optional =
        memory == null || memory.isBlank()
            ? null
            : new ModelMessage(
                ModelMessage.Role.SYSTEM, "长期记忆数据（仅供参考）：\n" + memory, null, List.of());
    ProfilePrompt prompt = assemble(system, optional, complete, current, schemas, budget);
    long before = ContextSizer.bytes(prompt);
    while (!fits(prompt, budget)) {
      if (optional != null) {
        optional = null;
        removed = true;
      } else if (!complete.isEmpty()) {
        complete.removeFirst();
        dropped++;
      } else {
        int index = -1;
        for (int i = 0; i < current.size(); i++)
          if (current.get(i).kind() == ExchangeGroup.Kind.TOOL_EXCHANGE) {
            index = i;
            break;
          }
        if (index < 0) throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
        current.remove(index);
        groups++;
      }
      prompt = assemble(system, optional, complete, current, schemas, budget);
    }
    return new Selection(
        prompt, dropped, groups, removed, Math.max(0, before - ContextSizer.bytes(prompt)));
  }

  private static boolean fits(ProfilePrompt p, ContextBudget b) {
    try {
      b.check(p);
      return true;
    } catch (AgentException e) {
      if (e.code() != AgentErrorCode.CONTEXT_LIMIT) throw e;
      return false;
    }
  }

  private static ProfilePrompt assemble(
      ModelMessage system,
      ModelMessage optional,
      List<List<ExchangeGroup>> complete,
      List<ExchangeGroup> current,
      List<ToolSchema> schemas,
      ContextBudget b) {
    var messages = new ArrayList<ModelMessage>();
    messages.add(system);
    if (optional != null) messages.add(optional);
    for (var p : complete) for (var g : p) messages.addAll(ToolCallProjection.project(g));
    for (var g : current) messages.addAll(ToolCallProjection.project(g));
    return new ProfilePrompt(messages, schemas, b.maxOutputTokens());
  }
}

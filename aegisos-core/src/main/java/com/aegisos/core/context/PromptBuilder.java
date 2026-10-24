package com.aegisos.core.context;

import com.aegisos.core.memory.*;
import com.aegisos.core.profile.Profile;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.ToolRegistryView;
import java.time.*;
import java.util.*;

/** 必需系统指令不能裁剪；全部内容先检查，再投影完整历史与工具交换。 */
public final class PromptBuilder {
  public record BuiltPrompt(
      ProfilePrompt prompt,
      List<String> warnings,
      int trimmedProcessings,
      int trimmedGroups,
      boolean trimmedMemory,
      long trimmedBytes) {
    public BuiltPrompt {
      warnings = List.copyOf(warnings);
    }

    public BuiltPrompt(ProfilePrompt p, List<String> w, int a, int b) {
      this(p, w, a, b, false, 0);
    }

    @Override
    public String toString() {
      return "BuiltPrompt[redacted]";
    }
  }

  private final AgentSourceRegistry sources;
  private final ContextSourceLoader loader;
  private final MemoryService memory;
  private final ToolRegistryView tools;
  private final ContextBudget budget;
  private final SensitiveContentGuard guard;
  private final Clock clock;
  private final ZoneId zone;

  public PromptBuilder(
      AgentSourceRegistry sources,
      ContextSourceLoader loader,
      MemoryService memory,
      ToolRegistryView tools,
      ContextBudget budget,
      SensitiveContentGuard guard,
      Clock clock,
      ZoneId zone) {
    this.sources = sources;
    this.loader = loader;
    this.memory = memory;
    this.tools = tools;
    this.budget = budget;
    this.guard = guard;
    this.clock = clock;
    this.zone = zone;
  }

  public BuiltPrompt build(
      Profile profile, com.aegisos.core.agent.ProcessingHandle handle, int historyTurns) {
    var schemas = tools.schemas().stream().filter(t -> profile.tools().contains(t.name())).toList();
    for (var schema : schemas) {
      guard.check(schema.name());
      guard.check(schema.description());
      guard.checkJson(schema.inputSchemaJson());
    }
    var loaded = loader.load(profile, sources.require(profile.name()));
    String role = profile.identity() == null ? null : profile.identity().prompt();
    guard.check(role);
    String instructions =
        (role == null || role.isBlank() ? "" : role + "\n\n")
            + loaded.instructions()
            + "\n\n当前时间："
            + ZonedDateTime.now(clock.withZone(zone))
            + "；时区："
            + zone.getId();
    guard.check(instructions);
    var history = memory.loadContextHistory(handle, historyTurns);
    for (var processing : history.completedProcessings())
      for (var group : processing) for (var message : group.messages()) check(message);
    for (var group : history.currentGroups()) for (var message : group.messages()) check(message);
    String recalled = null;
    boolean oversized = false;
    try {
      recalled =
          memory
              .recallLongTerm(
                  new MemoryQuery(
                      handle.sessionId(), handle.profileName(), budget.maxContextBytes()))
              .orElse(null);
      guard.check(recalled);
    } catch (LongTermMemoryLimitException e) {
      oversized = true;
    }
    var selection =
        ContextHistoryProjection.select(
            new ModelMessage(ModelMessage.Role.SYSTEM, instructions, null, List.of()),
            recalled,
            oversized,
            history,
            schemas,
            budget);
    guard.checkJson(ContextSizer.canonical(selection.prompt()));
    budget.check(selection.prompt());
    return new BuiltPrompt(
        selection.prompt(),
        loaded.warnings(),
        selection.processings(),
        selection.groups(),
        selection.memoryRemoved(),
        selection.bytesRemoved());
  }

  private void check(ModelMessage message) {
    guard.check(message.content());
    guard.check(message.toolCallId());
    for (var c : message.toolCalls()) {
      guard.check(c.id());
      guard.check(c.name());
      guard.checkJson(c.argumentsJson());
    }
  }
}

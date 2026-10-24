package com.aegisos.core.agent;

import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.Profile;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.util.*;

/** 自管理模型轮次；Provider 内部有限重试不在此再次重试，任何持久化故障均停止。 */
public final class ReActLoop {
  private final ProfileProviderService provider;
  private final MemoryService memory;
  private final PromptBuilder prompts;
  private final ToolExecutor tools;
  private final ContextBudget budget;
  private final SensitiveContentGuard guard;

  public ReActLoop(
      ProfileProviderService provider,
      MemoryService memory,
      PromptBuilder prompts,
      ToolExecutor tools,
      ContextBudget budget,
      SensitiveContentGuard guard) {
    this.provider = provider;
    this.memory = memory;
    this.prompts = prompts;
    this.tools = tools;
    this.budget = budget;
    this.guard = guard;
  }

  public ProcessingResult run(Profile profile, ProcessingHandle initial) {
    ProcessingHandle handle = initial;
    int accepted = 0;
    try {
      int max =
          profile.settings().maxIterations() == null ? 10 : profile.settings().maxIterations();
      int history =
          profile.settings().maxHistoryTurns() == null ? 20 : profile.settings().maxHistoryTurns();
      if (max < 1 || max > 100 || history < 1 || history > 1000)
        throw new AgentException(AgentErrorCode.INVALID_INPUT);
      for (int iteration = 1; iteration <= max; iteration++) {
        if (Thread.currentThread().isInterrupted())
          throw new AgentException(AgentErrorCode.INTERRUPTED);
        var built = prompts.build(profile, handle, history);
        for (String warning : built.warnings())
          handle = memory.recordEvent(handle, null, null, "WARN", warning, 1);
        if (built.trimmedMemory())
          handle =
              memory.recordEvent(handle, null, null, "TRUNCATED", "OPTIONAL_MEMORY_TRIMMED", 1);
        if (built.trimmedProcessings() > 0)
          handle =
              memory.recordEvent(
                  handle,
                  null,
                  null,
                  "TRUNCATED",
                  "HISTORY_PROCESSINGS_TRIMMED",
                  built.trimmedProcessings());
        if (built.trimmedGroups() > 0)
          handle =
              memory.recordEvent(
                  handle, null, null, "TRUNCATED", "CURRENT_GROUPS_TRIMMED", built.trimmedGroups());
        if (built.trimmedBytes() > 0)
          handle =
              memory.recordEvent(
                  handle, null, null, "TRUNCATED", "CONTEXT_BYTES_TRIMMED", built.trimmedBytes());
        handle = memory.prepareModelTurn(handle, iteration); // 必须提交 PREPARED 后才进入模型审计门槛。
        var response =
            provider.chatProfile(
                handle.sessionId(),
                profile.name(),
                built.prompt(),
                new ProviderInvocation(handle.processId(), iteration));
        check(response);
        var committed = memory.acceptModelResponse(handle, iteration, response);
        handle = committed.handle();
        accepted = committed.iterationCount();
        if (response.toolCalls().isEmpty())
          return memory.finish(handle, ProcessingStatus.COMPLETED, null, response.content());
        // 末轮意图已接受和持久化，但不得执行任何工具或再调用模型。
        if (iteration == max) {
          handle =
              memory.recordEvent(
                  handle, iteration, committed.groupId(), "STOPPED", "MAX_ITERATIONS", 1);
          return memory.finish(
              handle, ProcessingStatus.MAX_ITERATIONS, AgentErrorCode.MAX_ITERATIONS, null);
        }
        int ordinal = 0;
        for (var call : response.toolCalls()) {
          if (Thread.currentThread().isInterrupted())
            throw new AgentException(AgentErrorCode.INTERRUPTED);
          var observation =
              tools.execute(new ToolExecutionContext(handle, profile, iteration, ++ordinal), call);
          boolean reused = memory.findCommittedObservation(handle, call.id()).isPresent();
          handle = memory.commitToolObservation(handle, committed.groupId(), observation);
          if (reused)
            handle =
                memory.recordEvent(
                    handle,
                    iteration,
                    committed.groupId(),
                    "TOOL_REUSED",
                    "RELIABLE_OBSERVATION",
                    1);
          if (Thread.currentThread().isInterrupted())
            throw new AgentException(AgentErrorCode.INTERRUPTED);
        }
      }
      return memory.finish(
          handle, ProcessingStatus.MAX_ITERATIONS, AgentErrorCode.MAX_ITERATIONS, null);
    } catch (ProviderException e) {
      var code =
          Thread.currentThread().isInterrupted()
              ? AgentErrorCode.INTERRUPTED
              : e.failure().code() == ProviderErrorCode.AUDIT_UNAVAILABLE
                  ? AgentErrorCode.AUDIT_UNAVAILABLE
                  : AgentErrorCode.PROVIDER_FAILURE;
      return stop(handle, accepted, code);
    } catch (AgentException e) {
      return stop(handle, accepted, e.code());
    } catch (RuntimeException e) {
      return stop(handle, accepted, AgentErrorCode.PROVIDER_FAILURE);
    }
  }

  private void check(ModelResponse response) {
    if (response == null || response.requestId() == null)
      throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
    guard.check(response.content());
    guard.check(response.providerName());
    guard.check(response.modelName());
    guard.check(response.finishReason());
    if (response.toolCalls().size() > budget.maxToolsPerResponse())
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    var ids = new HashSet<String>();
    for (var call : response.toolCalls()) {
      guard.check(call.id());
      guard.check(call.name());
      guard.checkJson(call.argumentsJson());
      if (call.id() == null
          || !call.id().matches("[A-Za-z0-9_.-]{1,128}")
          || call.name() == null
          || !call.name().matches("[A-Za-z0-9_-]{1,64}")
          || call.argumentsJson() == null)
        throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
      if (!ids.add(call.id())) throw new AgentException(AgentErrorCode.TOOL_CALL_CONFLICT);
      if (ContextSizer.utf8(call.argumentsJson()) > budget.maxToolArgumentsBytes())
        throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    }
    if ((response.content() == null || response.content().isBlank())
        && response.toolCalls().isEmpty())
      throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
    if (ContextSizer.messageBytes(
            new ModelMessage(
                ModelMessage.Role.ASSISTANT, response.content(), null, response.toolCalls()))
        > budget.maxMessageBytes()) throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
  }

  private ProcessingResult stop(ProcessingHandle handle, int accepted, AgentErrorCode code) {
    try {
      handle =
          memory.recordEvent(
              handle,
              null,
              null,
              code == AgentErrorCode.TOOL_CALL_CONFLICT ? "TOOL_CONFLICT" : "STOPPED",
              code.name(),
              1);
      return memory.finish(
          handle,
          code == AgentErrorCode.INTERRUPTED
              ? ProcessingStatus.INTERRUPTED
              : ProcessingStatus.FAILED,
          code,
          null);
    } catch (RuntimeException e) {
      return new ProcessingResult(
          handle.sessionId(),
          handle.processId(),
          ProcessingStatus.FAILED,
          accepted,
          null,
          AgentErrorCode.PERSISTENCE_UNCONFIRMED,
          false);
    }
  }
}

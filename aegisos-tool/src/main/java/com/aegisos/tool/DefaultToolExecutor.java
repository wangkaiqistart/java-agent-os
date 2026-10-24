package com.aegisos.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.audit.ToolInvocationAuditStore;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.MemoryService;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.time.*;
import java.util.*;

/** 唯一执行边界。拒绝也持久审计；begin/complete 任一提交失败即中止，不重试副作用。 */
public final class DefaultToolExecutor implements ToolExecutor {
  private final ToolRegistryView registry;
  private final ToolArgumentsParser parser;
  private final Sandbox sandbox;
  private final ToolInvocationAuditStore audit;
  private final MemoryService memory;
  private final SensitiveContentGuard guard;
  private final ContextBudget budget;
  private final Clock clock;

  public DefaultToolExecutor(
      ToolRegistryView r,
      ToolArgumentsParser p,
      Sandbox s,
      ToolInvocationAuditStore a,
      MemoryService m,
      SensitiveContentGuard g,
      ContextBudget b,
      Clock c) {
    registry = r;
    parser = p;
    sandbox = s;
    audit = a;
    memory = m;
    guard = g;
    budget = b;
    clock = c;
  }

  public ToolObservation execute(ToolExecutionContext context, ToolCall call) {
    guard.check(call.id());
    guard.check(call.name());
    guard.checkJson(call.argumentsJson());
    ValidatedArguments args = null;
    AgentErrorCode rejected = null;
    try {
      args = parser.parse(call.name(), call.argumentsJson());
    } catch (AgentException e) {
      if (e.code() == AgentErrorCode.SENSITIVE_CONTENT) throw e;
      rejected = e.code();
    }
    String fingerprint =
        args == null
            ? ToolArgumentsParser.fingerprint(call.name(), call.argumentsJson())
            : args.fingerprint();
    Optional<ToolInvocationAuditStore.Record> existing;
    try {
      existing = audit.find(context.handle().processId(), call.id());
    } catch (RuntimeException e) {
      throw auditFailure();
    }
    if (existing.isPresent()) {
      var record = existing.get();
      if (!record.name().equals(call.name()) || !record.fingerprint().equals(fingerprint))
        throw new AgentException(AgentErrorCode.TOOL_CALL_CONFLICT);
      var observed = memory.findCommittedObservation(context.handle(), call.id());
      if (observed.isEmpty()
          || !Set.of("SUCCEEDED", "FAILED", "REJECTED").contains(record.status())
          || !observed.get().auditId().equals(record.id()))
        throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
      return observed.get();
    }
    var start =
        new ToolInvocationAuditStore.Start(
            UUID.randomUUID(),
            context.handle(),
            context.iterationNo(),
            context.callOrdinal(),
            call.id(),
            call.name(),
            fingerprint,
            clock.instant());
    AegisTool tool = registry.find(call.name()).orElse(null);
    ToolPermission permission = null;
    if (rejected == null) {
      if (tool == null) rejected = AgentErrorCode.UNKNOWN_TOOL;
      else if (!context.profile().tools().contains(call.name()))
        rejected = AgentErrorCode.TOOL_NOT_ALLOWED;
      else
        try {
          permission = sandbox.authorize(context, args);
        } catch (AgentException e) {
          rejected = e.code();
        }
    }
    if (rejected != null) {
      try {
        audit.reject(start, rejected);
      } catch (RuntimeException e) {
        throw auditFailure();
      }
      return observation(start, new ToolResult(false, rejected, ExternalOutcome.NOT_SENT, null));
    }
    try {
      try {
        audit.begin(start);
      } catch (RuntimeException e) {
        throw auditFailure();
      }
      long began = System.nanoTime();
      ToolResult result;
      try {
        result = tool.execute(context, args, permission);
        if (result == null
            || result.externalOutcome() == null
            || result.success() && result.errorCode() != null
            || !result.success() && result.errorCode() == null) throw new IllegalStateException();
      } catch (RuntimeException e) {
        result =
            new ToolResult(
                false,
                e instanceof AgentException a ? a.code() : AgentErrorCode.TOOL_EXECUTION_FAILED,
                ExternalOutcome.UNKNOWN,
                null);
      }
      boolean sensitive = false;
      try {
        guard.checkJson(result.data());
        if (ContextSizer.utf8(result.data()) > budget.maxToolResultBytes())
          result =
              new ToolResult(
                  false, AgentErrorCode.TOOL_RESPONSE_LIMIT, result.externalOutcome(), null);
      } catch (AgentException e) {
        sensitive = e.code() == AgentErrorCode.SENSITIVE_CONTENT;
        result = new ToolResult(false, e.code(), result.externalOutcome(), null);
      }
      if (!result.success())
        result = new ToolResult(false, result.errorCode(), result.externalOutcome(), null);
      try {
        audit.complete(
            start.id(),
            result,
            clock.instant(),
            Math.max(0, (System.nanoTime() - began) / 1000000));
      } catch (RuntimeException e) {
        throw auditFailure();
      }
      if (sensitive) throw new AgentException(AgentErrorCode.SENSITIVE_CONTENT);
      return observation(start, result);
    } finally {
      if (permission instanceof AutoCloseable closeable)
        try {
          closeable.close();
        } catch (Exception e) {
          /* 许可实现close幂等；不得透传资源异常正文。 */
        }
    }
  }

  private static ToolObservation observation(ToolInvocationAuditStore.Start s, ToolResult r) {
    return new ToolObservation(
        s.id(),
        s.rawCallId(),
        s.toolName(),
        r.success(),
        r.errorCode(),
        r.externalOutcome(),
        r.data());
  }

  private static AgentException auditFailure() {
    return new AgentException(AgentErrorCode.AUDIT_UNAVAILABLE);
  }
}

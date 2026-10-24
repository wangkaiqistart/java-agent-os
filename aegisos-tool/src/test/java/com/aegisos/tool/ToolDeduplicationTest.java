package com.aegisos.tool;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.audit.*;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ToolDeduplicationTest {
  static final class Fixture {
    final ContextBudget budget = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);
    final SensitiveContentGuard guard =
        new DefaultSensitiveContentGuard(List.of("synthetic-tool-key-123"));
    final AtomicInteger executions = new AtomicInteger();
    final AtomicInteger begins = new AtomicInteger();
    final AtomicInteger completions = new AtomicInteger();
    final Map<String, ToolInvocationAuditStore.Record> records = new HashMap<>();
    final Map<String, ToolObservation> observations = new HashMap<>();
    boolean beginFails, completeFails, denied;
    ToolResult next = new ToolResult(true, null, ExternalOutcome.RESPONDED, "safe");
    final ToolExecutionContext context = SandboxPolicyTest.context();

    ToolExecutor executor() {
      AegisTool tool =
          new AegisTool() {
            public ToolSchema schema() {
              return new HttpGetTool().schema();
            }

            public ToolResult execute(
                ToolExecutionContext c, ValidatedArguments a, ToolPermission p) {
              executions.incrementAndGet();
              return next;
            }
          };
      var memory =
          (MemoryService)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {MemoryService.class},
                  (p, m, a) -> {
                    if (m.getName().equals("findCommittedObservation"))
                      return Optional.ofNullable(observations.get(a[1]));
                    throw new AssertionError(m.getName());
                  });
      ToolInvocationAuditStore audit =
          new ToolInvocationAuditStore() {
            public void begin(Start s) {
              begins.incrementAndGet();
              if (beginFails) throw new AgentException(AgentErrorCode.AUDIT_UNAVAILABLE);
              records.put(
                  s.rawCallId(), new Record(s.id(), s.toolName(), s.fingerprint(), "STARTED"));
            }

            public void reject(Start s, AgentErrorCode code) {
              records.put(
                  s.rawCallId(), new Record(s.id(), s.toolName(), s.fingerprint(), "REJECTED"));
            }

            public void complete(UUID id, ToolResult result, Instant at, long ms) {
              completions.incrementAndGet();
              if (completeFails) throw new AgentException(AgentErrorCode.AUDIT_UNAVAILABLE);
              records.replaceAll(
                  (k, v) ->
                      v.id().equals(id)
                          ? new Record(
                              id,
                              v.name(),
                              v.fingerprint(),
                              result.success() ? "SUCCEEDED" : "FAILED")
                          : v);
            }

            public Optional<Record> find(UUID p, String id) {
              return Optional.ofNullable(records.get(id));
            }
          };
      return new DefaultToolExecutor(
          new ToolRegistry(List.of(tool)),
          new ToolArgumentsParser(budget, guard),
          (c, a) -> {
            if (denied) throw new AgentException(AgentErrorCode.SANDBOX_DENIED);
            return new ToolPermission() {};
          },
          audit,
          memory,
          guard,
          budget,
          Clock.systemUTC());
    }

    ToolCall call(String url) {
      return new ToolCall("id", "http_get", "{\"url\":" + ContextSizer.quote(url) + "}");
    }
  }

  @Test
  void completedAuditAndObservationAreBothRequiredToReuse() {
    var f = new Fixture();
    var e = f.executor();
    var call = f.call("https://example.org/weather");
    var first = e.execute(f.context, call);
    f.observations.put("id", first);
    assertThat(e.execute(f.context, call)).isEqualTo(first);
    assertThat(f.executions).hasValue(1);
    assertThatThrownBy(() -> e.execute(f.context, f.call("https://example.org/other")))
        .hasMessage("TOOL_CALL_CONFLICT");
    f.observations.clear();
    assertThatThrownBy(() -> e.execute(f.context, call)).hasMessage("PERSISTENCE_UNCONFIRMED");
    assertThat(f.executions).hasValue(1);
  }

  @Test
  void auditCommitIsMandatoryAndBusinessFailureIsObservation() {
    var f = new Fixture();
    f.beginFails = true;
    var e = f.executor();
    var firstFixture = f;
    assertThatThrownBy(
            () -> e.execute(firstFixture.context, firstFixture.call("https://example.org/weather")))
        .hasMessage("AUDIT_UNAVAILABLE");
    assertThat(f.executions).hasValue(0);
    f = new Fixture();
    f.completeFails = true;
    var e2 = f.executor();
    var f2 = f;
    assertThatThrownBy(() -> e2.execute(f2.context, f2.call("https://example.org/weather")))
        .hasMessage("AUDIT_UNAVAILABLE");
    assertThat(f.executions).hasValue(1);
    f = new Fixture();
    f.next = new ToolResult(false, AgentErrorCode.TOOL_TIMEOUT, ExternalOutcome.UNKNOWN, null);
    var result = f.executor().execute(f.context, f.call("https://example.org/weather"));
    assertThat(result.errorCode()).isEqualTo(AgentErrorCode.TOOL_TIMEOUT);
    assertThat(f.completions).hasValue(1);
  }

  @Test
  void sandboxDenialIsAuditedWithZeroExecutionAndSensitiveResultStops() {
    var f = new Fixture();
    f.denied = true;
    var result = f.executor().execute(f.context, f.call("https://example.org/weather"));
    assertThat(result.errorCode()).isEqualTo(AgentErrorCode.SANDBOX_DENIED);
    assertThat(result.externalOutcome()).isEqualTo(ExternalOutcome.NOT_SENT);
    assertThat(f.executions).hasValue(0);
    assertThat(f.records.get("id").status()).isEqualTo("REJECTED");
    var s = new Fixture();
    s.next = new ToolResult(true, null, ExternalOutcome.RESPONDED, "synthetic-tool-key-123");
    assertThatThrownBy(() -> s.executor().execute(s.context, s.call("https://example.org/weather")))
        .hasMessage("SENSITIVE_CONTENT");
    assertThat(s.completions).hasValue(1);
  }
}

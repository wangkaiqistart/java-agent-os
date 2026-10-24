package com.aegisos.core.audit;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.time.Instant;
import java.util.*;

/** 所有返回均表示独立事务已提交；不承载参数或结果正文。 */
public interface ToolInvocationAuditStore {
  record Start(
      UUID id,
      ProcessingHandle handle,
      int iterationNo,
      int callOrdinal,
      String rawCallId,
      String toolName,
      String fingerprint,
      Instant startedAt) {
    @Override
    public String toString() {
      return "ToolAuditStart[redacted]";
    }
  }

  record Record(UUID id, String name, String fingerprint, String status) {}

  void begin(Start start);

  void reject(Start start, AgentErrorCode code);

  void complete(UUID id, ToolResult result, Instant completedAt, long durationMs);

  Optional<Record> find(UUID processId, String callId);
}

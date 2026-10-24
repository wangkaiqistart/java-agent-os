package com.aegisos.core.memory;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.util.*;

/** 后端端口仅由 Memory 实现使用；每次写入短事务、归属与版本 CAS。 */
public interface SessionStore {
  SessionSnapshot createSession(String profileName);

  default SessionSnapshot getOrCreateSession(SessionIdentity identity) {
    throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
  }

  SessionSnapshot loadSession(String sessionId, String profileName);

  ProcessingHandle beginProcessing(
      String sessionId, String profileName, String userMessage, UUID runtimeInstanceId);

  ContextHistory loadContextHistory(ProcessingHandle handle, int maxHistoryTurns);

  ProcessingHandle prepareModelTurn(ProcessingHandle handle, int iterationNo);

  record Acceptance(ProcessingHandle handle, UUID groupId, int iterationCount) {}

  Acceptance acceptModelResponse(ProcessingHandle handle, int iterationNo, ModelResponse response);

  ProcessingHandle commitToolObservation(
      ProcessingHandle handle, UUID groupId, ToolObservation observation);

  Optional<ToolObservation> findCommittedObservation(ProcessingHandle handle, String callId);

  ProcessingHandle recordEvent(
      ProcessingHandle handle,
      Integer iterationNo,
      UUID groupId,
      String eventType,
      String reasonCode,
      long count);

  ProcessingResult finish(
      ProcessingHandle handle, ProcessingStatus status, AgentErrorCode error, String reply);
}

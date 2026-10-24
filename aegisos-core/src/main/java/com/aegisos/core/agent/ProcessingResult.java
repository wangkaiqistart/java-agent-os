package com.aegisos.core.agent;

import java.util.UUID;

public record ProcessingResult(
    String sessionId,
    UUID processId,
    ProcessingStatus status,
    int iterationCount,
    String reply,
    AgentErrorCode errorCode,
    boolean persistenceConfirmed) {
  public ProcessingResult {
    if (iterationCount < 0
        || status == null
        || (status == ProcessingStatus.COMPLETED
            && (!persistenceConfirmed || errorCode != null || reply == null)))
      throw new IllegalArgumentException("INVALID_RESULT");
  }

  @Override
  public String toString() {
    return "ProcessingResult[redacted]";
  }
}

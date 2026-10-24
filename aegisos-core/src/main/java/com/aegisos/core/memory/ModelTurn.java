package com.aegisos.core.memory;

import java.util.UUID;

public record ModelTurn(UUID processId, int iterationNo, Status status, UUID requestId) {
  public enum Status {
    PREPARED,
    ACCEPTED,
    FAILED,
    INTERRUPTED
  }

  public ModelTurn {
    if (processId == null || iterationNo < 1 || status == null)
      throw new IllegalArgumentException("INVALID_TURN");
  }
}

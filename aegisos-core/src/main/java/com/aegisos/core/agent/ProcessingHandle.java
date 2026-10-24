package com.aegisos.core.agent;

import java.util.*;

public record ProcessingHandle(
    UUID processId, String sessionId, String profileName, UUID runtimeInstanceId, long version) {
  public ProcessingHandle {
    Objects.requireNonNull(processId);
    Objects.requireNonNull(runtimeInstanceId);
    if (version < 0) throw new IllegalArgumentException("INVALID_HANDLE");
  }

  @Override
  public String toString() {
    return "ProcessingHandle[redacted]";
  }
}

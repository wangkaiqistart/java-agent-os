package com.aegisos.core.memory;

import com.aegisos.core.provider.ModelMessage;
import java.time.Instant;
import java.util.*;

public record SessionSnapshot(
    String sessionId,
    String profileName,
    Instant createdAt,
    Instant updatedAt,
    long version,
    List<ModelMessage> messages) {
  public SessionSnapshot {
    UUID.fromString(sessionId);
    if (version < 0) throw new IllegalArgumentException("INVALID_SESSION");
    messages = List.copyOf(messages);
  }

  @Override
  public String toString() {
    return "SessionSnapshot[redacted]";
  }
}

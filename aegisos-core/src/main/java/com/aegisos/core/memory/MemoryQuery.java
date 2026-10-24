package com.aegisos.core.memory;

public record MemoryQuery(String sessionId, String profileName, int maxBytes) {
  public MemoryQuery {
    if (maxBytes < 1) throw new IllegalArgumentException("INVALID_QUERY");
  }

  @Override
  public String toString() {
    return "MemoryQuery[redacted]";
  }
}

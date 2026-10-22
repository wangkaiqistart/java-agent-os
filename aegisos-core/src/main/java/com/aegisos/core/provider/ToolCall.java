package com.aegisos.core.provider;

public record ToolCall(String id, String name, String argumentsJson) {
  @Override
  public String toString() {
    return "ToolCall[redacted]";
  }
}

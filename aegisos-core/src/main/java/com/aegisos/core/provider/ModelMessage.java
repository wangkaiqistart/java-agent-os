package com.aegisos.core.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ModelMessage(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {
  public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
  }

  public ModelMessage {
    toolCalls = toolCalls == null ? null : Collections.unmodifiableList(new ArrayList<>(toolCalls));
  }

  @Override
  public String toString() {
    return "ModelMessage[redacted]";
  }
}

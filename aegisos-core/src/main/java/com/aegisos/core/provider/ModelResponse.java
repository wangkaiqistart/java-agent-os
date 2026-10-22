package com.aegisos.core.provider;

import java.util.List;
import java.util.UUID;

public record ModelResponse(
    UUID requestId,
    String providerName,
    String modelName,
    String content,
    List<ToolCall> toolCalls,
    String finishReason,
    TokenUsage usage) {
  public ModelResponse {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  @Override
  public String toString() {
    return "ModelResponse[redacted]";
  }
}

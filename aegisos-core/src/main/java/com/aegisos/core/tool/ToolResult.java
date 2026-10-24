package com.aegisos.core.tool;

import com.aegisos.core.agent.AgentErrorCode;
import com.aegisos.core.provider.ExternalOutcome;

public record ToolResult(
    boolean success, AgentErrorCode errorCode, ExternalOutcome externalOutcome, String data) {
  @Override
  public String toString() {
    return "ToolResult[redacted]";
  }
}

package com.aegisos.core.tool;

import com.aegisos.core.agent.ProcessingHandle;
import com.aegisos.core.profile.Profile;

public record ToolExecutionContext(
    ProcessingHandle handle, Profile profile, int iterationNo, int callOrdinal) {
  public ToolExecutionContext {
    if (handle == null || profile == null || iterationNo < 1 || callOrdinal < 1)
      throw new IllegalArgumentException("INVALID_TOOL_CONTEXT");
  }

  @Override
  public String toString() {
    return "ToolExecutionContext[redacted]";
  }
}

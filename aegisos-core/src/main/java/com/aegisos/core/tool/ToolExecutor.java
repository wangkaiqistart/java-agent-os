package com.aegisos.core.tool;

import com.aegisos.core.provider.ToolCall;

public interface ToolExecutor {
  ToolObservation execute(ToolExecutionContext context, ToolCall call);
}

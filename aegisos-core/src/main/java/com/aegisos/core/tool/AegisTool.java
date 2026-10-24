package com.aegisos.core.tool;

import com.aegisos.core.provider.ToolSchema;

public interface AegisTool {
  ToolSchema schema();

  ToolResult execute(
      ToolExecutionContext context, ValidatedArguments arguments, ToolPermission permission);
}

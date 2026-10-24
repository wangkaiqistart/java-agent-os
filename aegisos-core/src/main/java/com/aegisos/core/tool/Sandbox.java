package com.aegisos.core.tool;

public interface Sandbox {
  ToolPermission authorize(ToolExecutionContext context, ValidatedArguments arguments);
}

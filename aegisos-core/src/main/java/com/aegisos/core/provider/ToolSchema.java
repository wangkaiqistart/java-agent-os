package com.aegisos.core.provider;

public record ToolSchema(String name, String description, String inputSchemaJson) {
  @Override
  public String toString() {
    return "ToolSchema[redacted]";
  }
}

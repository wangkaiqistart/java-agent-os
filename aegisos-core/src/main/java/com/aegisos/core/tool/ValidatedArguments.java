package com.aegisos.core.tool;

import java.util.Map;

public record ValidatedArguments(
    Map<String, Object> values, String canonicalJson, String fingerprint) {
  public ValidatedArguments {
    values = Map.copyOf(values);
  }

  @Override
  public String toString() {
    return "ValidatedArguments[redacted]";
  }
}

package com.aegisos.boot.config;

import com.aegisos.core.context.ContextBudget;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aegisos.react")
public record ReactProperties(
    Integer maxContextBytes,
    Integer maxMessageBytes,
    Integer maxContextFileBytes,
    Integer maxContextFiles,
    Integer maxToolArgumentsBytes,
    Integer maxToolResultBytes,
    Integer maxToolsPerResponse,
    Integer maxOutputTokens,
    String zone) {
  public ContextBudget budget() {
    if (maxContextBytes == null) throw new IllegalArgumentException("INVALID_REACT_BUDGET");
    return new ContextBudget(
        maxContextBytes,
        value(maxMessageBytes, 32768),
        value(maxContextFileBytes, 32768),
        value(maxContextFiles, 32),
        value(maxToolArgumentsBytes, 8192),
        value(maxToolResultBytes, 16384),
        value(maxToolsPerResponse, 8),
        value(maxOutputTokens, 1024));
  }

  public ZoneId zoneId() {
    try {
      return ZoneId.of(zone == null ? "UTC" : zone);
    } catch (java.time.DateTimeException e) {
      throw new IllegalArgumentException("INVALID_REACT_ZONE");
    }
  }

  private static int value(Integer n, int fallback) {
    return n == null ? fallback : n;
  }
}

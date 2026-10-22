package com.aegisos.core.provider;

public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens) {
  public TokenUsage {
    nonnegative(inputTokens);
    nonnegative(outputTokens);
    nonnegative(totalTokens);
  }

  private static void nonnegative(Long n) {
    if (n != null && n < 0) throw new IllegalArgumentException("Token usage must be nonnegative");
  }
}

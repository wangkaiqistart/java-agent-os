package com.aegisos.boot.config;

import com.aegisos.core.profile.Profile;

public record ProfileRuntimePolicy(int maxIterations, int maxHistoryTurns) {
  public static ProfileRuntimePolicy from(Profile p) {
    int max = p.settings().maxIterations() == null ? 10 : p.settings().maxIterations();
    int history = p.settings().maxHistoryTurns() == null ? 20 : p.settings().maxHistoryTurns();
    if (max < 1 || max > 100 || history < 1 || history > 1000)
      throw new IllegalArgumentException("INVALID_PROFILE_RUNTIME_POLICY");
    return new ProfileRuntimePolicy(max, history);
  }
}

package com.aegisos.core.context;

import java.nio.file.Path;
import java.util.Objects;

public record AgentSource(
    String profileName, Path workspaceRoot, Path agentDirectory, Path relativeDirectory) {
  public AgentSource {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(workspaceRoot);
    Objects.requireNonNull(agentDirectory);
    Objects.requireNonNull(relativeDirectory);
    if (!workspaceRoot.isAbsolute()
        || !agentDirectory.isAbsolute()
        || relativeDirectory.isAbsolute()
        || relativeDirectory.normalize().startsWith(".."))
      throw new IllegalArgumentException("INVALID_SOURCE");
  }

  @Override
  public String toString() {
    return "AgentSource[redacted]";
  }
}

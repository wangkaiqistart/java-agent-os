package com.aegisos.core.agent;

public final class AgentException extends RuntimeException {
  private final AgentErrorCode code;

  public AgentException(AgentErrorCode code) {
    super(code.name(), null, false, false);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public AgentErrorCode code() {
    return code;
  }
}

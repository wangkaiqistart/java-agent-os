package com.aegisos.core.memory;

public final class SessionQueryException extends RuntimeException {
  public enum State {
    DATABASE_NOT_INITIALIZED,
    MIGRATION_REQUIRED,
    QUERY_UNAVAILABLE
  }

  private final State state;

  public SessionQueryException(State state) {
    super(state.name());
    this.state = state;
  }

  public State state() {
    return state;
  }
}

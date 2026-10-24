package com.aegisos.core.memory;

import com.aegisos.core.provider.ModelMessage;
import java.util.*;

public record ExchangeGroup(
    UUID groupId,
    UUID processId,
    int groupOrdinal,
    Kind kind,
    State state,
    Integer iterationNo,
    int expectedObservationCount,
    int committedObservationCount,
    List<ModelMessage> messages) {
  public enum Kind {
    USER,
    TEXT,
    TOOL_EXCHANGE
  }

  public enum State {
    OPEN,
    CLOSED
  }

  public ExchangeGroup {
    messages = List.copyOf(messages);
    if (groupOrdinal < 0
        || expectedObservationCount < 0
        || committedObservationCount < 0
        || committedObservationCount > expectedObservationCount
        || state == State.CLOSED && expectedObservationCount != committedObservationCount)
      throw new IllegalArgumentException("INVALID_GROUP");
  }

  @Override
  public String toString() {
    return "ExchangeGroup[redacted]";
  }
}

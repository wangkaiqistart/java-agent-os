package com.aegisos.core.tool;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.ExternalOutcome;
import java.util.*;

public record ToolObservation(
    UUID auditId,
    String callId,
    String name,
    boolean success,
    AgentErrorCode errorCode,
    ExternalOutcome externalOutcome,
    String data) {
  public ToolObservation {
    if (auditId == null
        || callId == null
        || !callId.matches("[A-Za-z0-9_.-]{1,128}")
        || name == null
        || !name.matches("[A-Za-z0-9_-]{1,64}")
        || externalOutcome == null
        || success && errorCode != null
        || !success && errorCode == null) throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
  }

  @Override
  public String toString() {
    return "ToolObservation[redacted]";
  }
}

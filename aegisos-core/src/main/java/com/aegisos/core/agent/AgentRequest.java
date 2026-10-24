package com.aegisos.core.agent;

public record AgentRequest(String sessionId, String profileName, String userMessage) {
  @Override
  public String toString() {
    return "AgentRequest[redacted]";
  }
}

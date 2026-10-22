package com.aegisos.core.audit;

public final class AuditUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public AuditUnavailableException() {
    super("AUDIT_UNAVAILABLE", null, false, false);
  }
}

package com.aegisos.core.tool;

import com.aegisos.core.context.ContextSizer;

/** 观察消息的固定形状；不包含自由错误说明。 */
public final class ToolObservationJson {
  private ToolObservationJson() {}

  public static String encode(ToolObservation o) {
    return "{\"auditId\":"
        + ContextSizer.quote(o.auditId().toString())
        + ",\"callId\":"
        + ContextSizer.quote(o.callId())
        + ",\"name\":"
        + ContextSizer.quote(o.name())
        + ",\"success\":"
        + o.success()
        + ",\"errorCode\":"
        + ContextSizer.quote(o.errorCode() == null ? null : o.errorCode().name())
        + ",\"externalOutcome\":"
        + ContextSizer.quote(o.externalOutcome().name())
        + ",\"data\":"
        + ContextSizer.quote(o.data())
        + "}";
  }
}

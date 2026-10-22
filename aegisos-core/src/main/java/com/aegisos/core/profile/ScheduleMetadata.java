package com.aegisos.core.profile;

import java.time.ZoneId;

/** 只校验配置结构和时区，不解释 cron 或注册调度任务。 */
public record ScheduleMetadata(
    String key, String cron, String zone, String message, String displayName) {
  public ScheduleMetadata {
    Profile.requireName(key);
    if (cron == null || cron.isBlank() || message == null || message.isBlank() || zone == null)
      throw MetadataValue.invalid();
    try {
      ZoneId.of(zone);
    } catch (RuntimeException e) {
      throw MetadataValue.invalid();
    }
  }

  @Override
  public String toString() {
    return "ScheduleMetadata[redacted]";
  }
}

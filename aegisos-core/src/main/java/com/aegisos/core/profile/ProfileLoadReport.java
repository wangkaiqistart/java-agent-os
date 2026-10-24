package com.aegisos.core.profile;

import java.util.List;

/** 加载报告不保留解析树和失败配置；可供受控诊断读取。 */
public record ProfileLoadReport(
    List<Profile> profiles,
    List<ProfileDiagnostic> diagnostics,
    com.aegisos.core.context.AgentSourceRegistry sources) {
  public ProfileLoadReport(List<Profile> profiles, List<ProfileDiagnostic> diagnostics) {
    this(profiles, diagnostics, new com.aegisos.core.context.AgentSourceRegistry(List.of()));
  }

  public ProfileLoadReport {
    profiles = List.copyOf(profiles);
    diagnostics = List.copyOf(diagnostics);
  }

  @Override
  public String toString() {
    return "ProfileLoadReport[profiles="
        + profiles.size()
        + ", diagnostics="
        + diagnostics.size()
        + "]";
  }
}

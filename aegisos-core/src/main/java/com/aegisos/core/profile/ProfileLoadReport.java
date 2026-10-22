package com.aegisos.core.profile;

import java.util.List;

/** 加载报告不保留解析树和失败配置；可供受控诊断读取。 */
public record ProfileLoadReport(List<Profile> profiles, List<ProfileDiagnostic> diagnostics) {
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

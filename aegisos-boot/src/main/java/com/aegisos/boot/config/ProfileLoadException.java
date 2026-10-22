package com.aegisos.boot.config;

import com.aegisos.core.profile.ProfileDiagnostic.Code;

/** 内部安全错误只保留固定类别，不保留解析器片段、字段值或 cause。 */
final class ProfileLoadException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final Code code;

  ProfileLoadException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }
}

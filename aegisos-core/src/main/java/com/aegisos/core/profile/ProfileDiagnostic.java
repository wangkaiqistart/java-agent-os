package com.aegisos.core.profile;

/** 诊断只包含固定代码及安全相对来源，不携带输入、异常或环境值。 */
public record ProfileDiagnostic(Code code, String source) {
  public enum Code {
    READ_FAILED,
    INVALID_YAML,
    LIMIT_EXCEEDED,
    INVALID_FIELD,
    ENV_MISSING,
    INVALID_SKILL_BINDING,
    UNKNOWN_PROVIDER,
    MODEL_UNAVAILABLE,
    DUPLICATE_PROFILE,
    PROFILE_FILE_MISSING,
    AGENT_ROOT_MISSING,
    AGENT_ROOT_EMPTY
  }

  public ProfileDiagnostic {
    if (code == null) throw MetadataValue.invalid();
    if (source == null
        || !source.matches("[A-Za-z0-9_./<>-]{1,256}")
        || source.contains("..")
        || source.startsWith("/")) source = "<invalid-source>";
  }
}

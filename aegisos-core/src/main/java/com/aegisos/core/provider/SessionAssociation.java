package com.aegisos.core.provider;

/** 会话仅是受限追踪值，不证明会话存在或已认证；旧入口使用明确的独立模式。 */
public final class SessionAssociation {
  public enum Mode {
    LEGACY_UNASSOCIATED,
    SESSION_REQUIRED
  }

  private SessionAssociation() {}

  /** 返回可入库的合法值，非法值永不保留、trim 或反射到异常。 */
  public static String safe(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") ? value : null;
  }

  public static ProviderErrorCode error(Mode mode, String value) {
    if (mode == Mode.LEGACY_UNASSOCIATED) return null;
    return value == null
        ? ProviderErrorCode.SESSION_REQUIRED
        : safe(value) == null ? ProviderErrorCode.INVALID_SESSION_ID : null;
  }
}

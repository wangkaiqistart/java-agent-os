package com.aegisos.core.memory;

/** 精确、大小写敏感的逻辑会话身份；独立字段比较，不能用分隔符拼接，也不代表认证身份。 */
public record SessionIdentity(String channel, String user, String profile) {
  public SessionIdentity {
    if (channel == null
        || !channel.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
        || user == null
        || !user.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
        || profile == null
        || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("INVALID_SESSION_IDENTITY");
  }

  @Override
  public String toString() {
    return "SessionIdentity[redacted]";
  }
}

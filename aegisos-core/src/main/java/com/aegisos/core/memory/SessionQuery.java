package com.aegisos.core.memory;

/** 只读分页在进入存储前校验，不允许隐式无界查询。 */
public record SessionQuery(String profile, int limit, int offset) {
  public SessionQuery() {
    this(null, 20, 0);
  }

  public SessionQuery {
    if (limit < 1
        || limit > 100
        || offset < 0
        || offset > 100000
        || profile != null && !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("INVALID_SESSION_QUERY");
  }
}

package com.aegisos.boot.config;

import com.aegisos.core.context.*;
import java.util.*;

/** 只装入实际 Provider 解析过的凭据；不扫描全部环境，不暴露值/哈希/数量。 */
public final class RuntimeSecretSnapshot {
  private final SensitiveContentGuard guard;

  public RuntimeSecretSnapshot(Collection<String> resolved) {
    guard = new DefaultSensitiveContentGuard(resolved);
  }

  public SensitiveContentGuard guard() {
    return guard;
  }

  @Override
  public String toString() {
    return "RuntimeSecretSnapshot[redacted]";
  }
}

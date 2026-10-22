package com.aegisos.boot.config;

import java.util.Objects;
import java.util.function.Function;

/** 环境变量引用是配置的一部分，凭证只在启动装配期间解析。 */
public final class EnvironmentCredentialResolver {
  private final Function<String, String> source;

  public EnvironmentCredentialResolver(Function<String, String> source) {
    this.source = Objects.requireNonNull(source);
  }

  public String resolve(String reference) {
    if (reference == null || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) return null;
    try {
      String value = source.apply(reference.substring(4));
      return value == null || value.isBlank() ? null : value;
    } catch (RuntimeException e) {
      return null;
    }
  }
}

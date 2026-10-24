package com.aegisos.boot.config;

import java.util.Objects;
import java.util.function.Function;

/** 环境变量引用是配置的一部分，凭证只在启动装配期间解析。 */
public final class EnvironmentCredentialResolver {
  private final Function<String, String> source;
  private final java.util.Map<String, java.util.Optional<String>> resolved =
      new java.util.HashMap<>();

  public EnvironmentCredentialResolver(Function<String, String> source) {
    this.source = Objects.requireNonNull(source);
  }

  public synchronized RuntimeSecretSnapshot snapshot() {
    return new RuntimeSecretSnapshot(
        resolved.values().stream().flatMap(java.util.Optional::stream).toList());
  }

  public synchronized String resolve(String reference) {
    if (reference == null || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) return null;
    if (resolved.containsKey(reference)) return resolved.get(reference).orElse(null);
    try {
      String value = source.apply(reference.substring(4));
      String safe = value == null || value.isBlank() ? null : value;
      resolved.put(reference, java.util.Optional.ofNullable(safe));
      return safe;
    } catch (RuntimeException e) {
      resolved.put(reference, java.util.Optional.empty());
      return null;
    }
  }

  @Override
  public String toString() {
    return "EnvironmentCredentialResolver[redacted]";
  }
}

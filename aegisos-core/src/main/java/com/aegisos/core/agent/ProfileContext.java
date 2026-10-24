package com.aegisos.core.agent;

/** 普通 ThreadLocal 的同步作用域，绝不用于审计归属，也不传播给后台线程。 */
public final class ProfileContext {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  public static String current() {
    return CURRENT.get();
  }

  public static Scope enter(String profile) {
    if (CURRENT.get() != null || profile == null)
      throw new AgentException(AgentErrorCode.INVALID_INPUT);
    CURRENT.set(profile);
    Thread owner = Thread.currentThread();
    return () -> {
      if (Thread.currentThread() != owner) throw new AgentException(AgentErrorCode.INVALID_INPUT);
      CURRENT.remove();
    };
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}

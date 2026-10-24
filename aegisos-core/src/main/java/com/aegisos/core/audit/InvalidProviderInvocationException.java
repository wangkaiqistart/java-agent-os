package com.aegisos.core.audit;

/** 不受信任的处理关联。与“有效关联提交失败”区分，以安全的无关联 INVALID_REQUEST 拒绝。 */
public final class InvalidProviderInvocationException extends RuntimeException {
  public InvalidProviderInvocationException() {
    super("INVALID_REQUEST", null, false, false);
  }
}

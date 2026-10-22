package com.aegisos.core.provider;

/** 只允许安全失败信息，绝不保留 SDK cause。 */
public final class ProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final ProviderFailure failure;

  public ProviderException(ProviderFailure failure) {
    super(failure.code().name(), null, false, false);
    this.failure = failure;
  }

  public ProviderFailure failure() {
    return failure;
  }
}

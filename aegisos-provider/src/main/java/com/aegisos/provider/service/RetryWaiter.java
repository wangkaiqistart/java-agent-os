package com.aegisos.provider.service;

@FunctionalInterface
public interface RetryWaiter {
  void pause() throws InterruptedException;

  static RetryWaiter fixed() {
    return () -> Thread.sleep(100);
  }
}

package com.aegisos.provider.adapter;

/** 仅记录一次物理 HTTP 头发送尝试，不接收 URL/头/正文或凭据。 */
@FunctionalInterface
public interface TransportSendObserver {
  TransportSendObserver NOOP = () -> {};

  void onSend();
}

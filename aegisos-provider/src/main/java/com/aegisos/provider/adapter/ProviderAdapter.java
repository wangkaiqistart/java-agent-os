package com.aegisos.provider.adapter;

import com.aegisos.core.provider.*;

/** 只调用一次；重试、审计和 requestId 由服务编排。 */
@FunctionalInterface
public interface ProviderAdapter extends AutoCloseable {
  ModelResponse chat(ModelRequest request);

  @Override
  default void close() {}
}

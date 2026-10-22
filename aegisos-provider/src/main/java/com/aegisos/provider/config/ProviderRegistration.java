package com.aegisos.provider.config;

import com.aegisos.core.provider.ProviderErrorCode;
import com.aegisos.provider.adapter.ProviderAdapter;

public record ProviderRegistration(
    ProviderDefinition definition, ProviderAdapter adapter, ProviderErrorCode unavailableReason) {
  public ProviderRegistration {
    if (definition == null || (adapter == null) == (unavailableReason == null))
      throw new IllegalArgumentException("Invalid provider registration");
  }

  public boolean available() {
    return adapter != null;
  }

  @Override
  public String toString() {
    return "ProviderRegistration[redacted]";
  }
}

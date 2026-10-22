package com.aegisos.core.profile;

import com.aegisos.core.provider.SafeProviderMetadata;

/** 显式选择；允许集合由加载器校验，缺省温度保持 null。 */
public record ProviderSelection(String name, String model, Double temperature) {
  public ProviderSelection {
    if (SafeProviderMetadata.provider(name) == null
        || SafeProviderMetadata.model(model) == null
        || (temperature != null
            && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)))
      throw MetadataValue.invalid();
  }

  @Override
  public String toString() {
    return "ProviderSelection[redacted]";
  }
}

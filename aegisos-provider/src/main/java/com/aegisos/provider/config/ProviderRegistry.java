package com.aegisos.provider.config;

import com.aegisos.core.provider.SafeProviderMetadata;
import java.util.*;

/** 大小写敏感的启动快照；完全不通过 Bean 类型或注册顺序猜测。 */
public final class ProviderRegistry implements AutoCloseable {
  private final Map<String, ProviderRegistration> entries;

  public ProviderRegistry(Collection<ProviderRegistration> registrations) {
    var map = new LinkedHashMap<String, ProviderRegistration>();
    for (var r : registrations) {
      if (r == null
          || SafeProviderMetadata.provider(r.definition().name()) == null
          || map.putIfAbsent(r.definition().name(), r) != null)
        throw new IllegalArgumentException("Invalid or duplicate provider identity");
    }
    this.entries = Collections.unmodifiableMap(map);
  }

  public ProviderRegistration get(String name) {
    return entries.get(name);
  }

  public Collection<ProviderRegistration> registrations() {
    return entries.values();
  }

  public void close() {
    entries.values().stream()
        .filter(ProviderRegistration::available)
        .map(ProviderRegistration::adapter)
        .distinct()
        .forEach(com.aegisos.provider.adapter.ProviderAdapter::close);
  }
}

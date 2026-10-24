package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import java.util.*;

public final class AgentSourceRegistry {
  private final Map<String, AgentSource> sources;

  public AgentSourceRegistry(Collection<AgentSource> sources) {
    var map = new LinkedHashMap<String, AgentSource>();
    for (var source : sources)
      if (map.putIfAbsent(source.profileName(), source) != null)
        throw new IllegalArgumentException("DUPLICATE_SOURCE");
    this.sources = Map.copyOf(map);
  }

  public AgentSource require(String profileName) {
    var source = sources.get(profileName);
    if (source == null) throw new AgentException(AgentErrorCode.UNKNOWN_PROFILE);
    return source;
  }

  public Map<String, AgentSource> sources() {
    return sources;
  }

  @Override
  public String toString() {
    return "AgentSourceRegistry[redacted]";
  }
}

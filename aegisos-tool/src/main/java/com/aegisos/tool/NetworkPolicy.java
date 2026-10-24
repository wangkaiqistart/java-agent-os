package com.aegisos.tool;

import java.time.Duration;
import java.util.*;

/** 部署网络约束；空集合为拒绝所有目标。 */
public record NetworkPolicy(
    List<Target> targets,
    Duration dns,
    Duration connect,
    Duration read,
    Duration execution,
    int maxResponseBytes) {
  public record Target(String scheme, String host, int port, String path, Set<String> queryKeys) {
    public Target {
      queryKeys = Set.copyOf(queryKeys);
    }
  }

  public NetworkPolicy {
    targets = List.copyOf(targets);
  }

  public static NetworkPolicy defaults(List<Target> t) {
    return new NetworkPolicy(
        t,
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        8192);
  }
}

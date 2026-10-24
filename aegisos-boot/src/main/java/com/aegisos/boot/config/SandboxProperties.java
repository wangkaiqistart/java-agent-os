package com.aegisos.boot.config;

import com.aegisos.core.context.ContextBudget;
import com.aegisos.tool.NetworkPolicy;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aegisos.sandbox")
public record SandboxProperties(HttpGet httpGet) {
  public record Target(
      String scheme,
      String host,
      Integer port,
      String method,
      List<String> paths,
      List<String> queryKeys) {
    public Target {
      paths = paths == null ? null : List.copyOf(paths);
      queryKeys = queryKeys == null ? null : List.copyOf(queryKeys);
    }
  }

  public record HttpGet(
      Duration dnsTimeout,
      Duration connectTimeout,
      Duration readTimeout,
      Duration executionTimeout,
      Integer maxResponseBytes,
      List<Target> targets) {
    public HttpGet {
      targets = targets == null ? null : List.copyOf(targets);
    }
  }

  public NetworkPolicy policy(ContextBudget budget) {
    try {
      HttpGet source = httpGet == null ? new HttpGet(null, null, null, null, null, null) : httpGet;
      Duration dns = value(source.dnsTimeout(), 2),
          connect = value(source.connectTimeout(), 3),
          read = value(source.readTimeout(), 5),
          execution = value(source.executionTimeout(), 10);
      for (var d : List.of(dns, connect, read, execution))
        if (d.isZero() || d.isNegative() || d.compareTo(Duration.ofSeconds(30)) > 0)
          throw invalid();
      for (var d : List.of(dns, connect, read)) if (d.compareTo(execution) > 0) throw invalid();
      int max = source.maxResponseBytes() == null ? 8192 : source.maxResponseBytes();
      if (max < 1 || max > budget.maxToolResultBytes()) throw invalid();
      var targets = new ArrayList<NetworkPolicy.Target>();
      var unique = new HashSet<String>();
      if (source.targets() != null)
        for (var target : source.targets()) {
          if (!"https".equals(target.scheme())
              || target.host() == null
              || !target.host().matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")
              || target.host().matches("[0-9.]+")
              || target.port() == null
              || target.port() < 1
              || target.port() > 65535
              || !"GET".equals(target.method())
              || target.paths() == null
              || target.paths().isEmpty()
              || target.queryKeys() == null) throw invalid();
          var keys = new HashSet<String>();
          for (String key : target.queryKeys())
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !keys.add(key))
              throw invalid();
          for (String path : target.paths()) {
            if (path == null
                || !path.matches("/[A-Za-z0-9_./-]*")
                || path.contains("//")
                || !java.net.URI.create(path).normalize().toString().equals(path)) throw invalid();
            if (!unique.add(target.host() + ":" + target.port() + path)) throw invalid();
            targets.add(
                new NetworkPolicy.Target(
                    target.scheme(), target.host(), target.port(), path, keys));
          }
        }
      return new NetworkPolicy(targets, dns, connect, read, execution, max);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static Duration value(Duration d, int seconds) {
    return d == null ? Duration.ofSeconds(seconds) : d;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_SANDBOX_CONFIGURATION");
  }
}

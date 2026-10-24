package com.aegisos.boot.config;

import com.aegisos.provider.config.ProviderDefinition;
import com.aegisos.provider.config.ProviderThinkingMode;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegisos")
public class ProviderProperties {
  private List<Entry> providers = new ArrayList<>();

  public List<Entry> getProviders() {
    return providers == null ? null : new ArrayList<>(providers);
  }

  public void setProviders(List<Entry> value) {
    providers = value == null ? null : new ArrayList<>(value);
  }

  /** 非法单项用不可用快照表示；无效/重复身份阻止启动，不带原始配置。 */
  public List<ProviderDefinition> snapshot(boolean test) {
    var definitions = new ArrayList<ProviderDefinition>();
    var names = new HashSet<String>();
    if (providers == null) throw new IllegalArgumentException("Invalid provider list");
    for (var e : providers) {
      if (e == null
          || com.aegisos.core.provider.SafeProviderMetadata.provider(e.name) == null
          || !names.add(e.name))
        throw new IllegalArgumentException("Invalid or duplicate provider identity");
      var models = new LinkedHashSet<String>();
      if (e.models != null)
        for (String model : e.models) {
          if (com.aegisos.core.provider.SafeProviderMetadata.model(model) == null) {
            models.clear();
            break;
          }
          models.add(model);
        }
      int retries = 0;
      try {
        retries = e.maxRetries == null ? 0 : Integer.parseInt(e.maxRetries);
        if (retries < 0) retries = -1;
      } catch (RuntimeException ignored) {
        retries = -1;
      }
      java.net.URI endpoint = endpoint(e.baseUrl, test);
      var connect = duration(e.connectTimeout, java.time.Duration.ofSeconds(5));
      var read = duration(e.readTimeout, java.time.Duration.ofSeconds(60));
      String ref =
          e.credentialRef != null && e.credentialRef.matches("env:[A-Za-z_][A-Za-z0-9_]*")
              ? e.credentialRef
              : null;
      definitions.add(
          new ProviderDefinition(
              e.name,
              "openai-compatible".equals(e.adapter) ? e.adapter : "unsupported",
              models,
              endpoint,
              ref,
              retries,
              connect,
              read,
              e.thinkingMode == null
                  ? ProviderThinkingMode.UNSPECIFIED
                  : "disabled".equals(e.thinkingMode) ? ProviderThinkingMode.DISABLED : null));
    }
    return List.copyOf(definitions);
  }

  private static java.time.Duration duration(String text, java.time.Duration fallback) {
    if (text == null) return fallback;
    try {
      var d = org.springframework.boot.convert.DurationStyle.detectAndParse(text);
      return d.isPositive() && d.toMillis() <= Integer.MAX_VALUE ? d : java.time.Duration.ZERO;
    } catch (RuntimeException ignored) {
      return java.time.Duration.ZERO;
    }
  }

  private static java.net.URI endpoint(String text, boolean test) {
    try {
      var uri = java.net.URI.create(text == null ? "https://api.openai.com" : text);
      String host = uri.getHost();
      boolean loopback =
          Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host == null ? "" : host);
      String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
      if (uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || host == null
          || uri.getPort() > 65535
          || uri.getPort() == 0
          || path.endsWith("/v1")
          || path.endsWith("/chat/completions")) return null;
      if (!"https".equalsIgnoreCase(uri.getScheme())
          && !(test && loopback && "http".equalsIgnoreCase(uri.getScheme()))) return null;
      return uri;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  @Override
  public String toString() {
    return "ProviderProperties[redacted]";
  }

  public static class Entry {
    private String thinkingMode;

    public String getThinkingMode() {
      return thinkingMode;
    }

    public void setThinkingMode(String value) {
      thinkingMode = value;
    }

    private String name;

    public String getName() {
      return name;
    }

    public void setName(String value) {
      this.name = value;
    }

    private String adapter;

    public String getAdapter() {
      return adapter;
    }

    public void setAdapter(String value) {
      this.adapter = value;
    }

    private java.util.List<String> models;

    public java.util.List<String> getModels() {
      return models == null ? null : new ArrayList<>(models);
    }

    public void setModels(java.util.List<String> value) {
      this.models = value == null ? null : new ArrayList<>(value);
    }

    private String baseUrl;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String value) {
      this.baseUrl = value;
    }

    private String credentialRef;

    public String getCredentialRef() {
      return credentialRef;
    }

    public void setCredentialRef(String value) {
      this.credentialRef = value;
    }

    private String maxRetries;

    public String getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(String value) {
      this.maxRetries = value;
    }

    private String connectTimeout;

    public String getConnectTimeout() {
      return connectTimeout;
    }

    public void setConnectTimeout(String value) {
      this.connectTimeout = value;
    }

    private String readTimeout;

    public String getReadTimeout() {
      return readTimeout;
    }

    public void setReadTimeout(String value) {
      this.readTimeout = value;
    }

    @Override
    public String toString() {
      return "ProviderEntry[redacted]";
    }
  }
}

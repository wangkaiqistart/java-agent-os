package com.aegisos.boot.cli;

import com.aegisos.boot.config.*;
import com.aegisos.core.context.DefaultSensitiveContentGuard;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.ProviderErrorCode;
import com.aegisos.provider.config.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 离线元数据快照：复用生产解析器，但绝不创建客户端、解析凭据或装配存储。 */
public final class WorkspaceMetadataService {
  private final Map<String, Object> properties;
  private final List<ProviderDefinition> providers;
  private final ProfileLoadReport report;

  public WorkspaceMetadataService(Path root) {
    properties = new CliWorkspaceConfiguration().load(root);
    var source = new MapConfigurationPropertySource(properties);
    var bean =
        new Binder(source)
            .bind("aegisos", Bindable.of(ProviderProperties.class))
            .orElseGet(ProviderProperties::new);
    providers = bean.snapshot(false);
    var metadata =
        providers.stream()
            .map(d -> new ProviderRegistration(d, null, ProviderErrorCode.PROVIDER_UNAVAILABLE))
            .toList();
    // unavailable在这里表示“没有实例化执行客户端”，不能误报Profile配置非法。
    report = new ProfileLoader(root, new ProviderRegistry(metadata), System::getenv).load();
  }

  public Map<String, Object> properties() {
    return Map.copyOf(properties);
  }

  public List<ProviderDefinition> providers() {
    return List.copyOf(providers);
  }

  public ProfileLoadReport report() {
    return report;
  }

  public static Map<String, Object> profile(Profile p) {
    var safe = new DefaultSensitiveContentGuard(List.of());
    safe.check(p.name());
    safe.check(p.provider().name());
    safe.check(p.provider().model());
    return Map.of(
        "name",
        p.name(),
        "provider",
        p.provider().name(),
        "model",
        p.provider().model(),
        "status",
        "CONFIGURED");
  }

  public Map<String, Object> profiles() {
    return Map.of(
        "items",
        report.profiles().stream().map(WorkspaceMetadataService::profile).toList(),
        "diagnostics",
        report.diagnostics().stream().map(d -> d.code().name()).toList());
  }

  public Map<String, Object> show(String name) {
    return report.profiles().stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .map(WorkspaceMetadataService::profile)
        .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_PROFILE"));
  }

  public Map<String, Object> providerList() {
    var guard = new DefaultSensitiveContentGuard(List.of());
    return Map.of(
        "items",
        providers.stream()
            .map(
                d -> {
                  guard.check(d.name());
                  d.models().forEach(guard::check);
                  boolean valid =
                      "openai-compatible".equals(d.adapter())
                          && d.baseUrl() != null
                          && d.credentialRef() != null
                          && d.thinkingMode() != null
                          && d.maxRetries() >= 0
                          && !d.models().isEmpty()
                          && d.connectTimeout().isPositive()
                          && d.readTimeout().isPositive();
                  return Map.of(
                      "name",
                      d.name(),
                      "models",
                      d.models().stream().sorted().toList(),
                      "adapter",
                      d.adapter(),
                      "status",
                      valid ? "CONFIGURED_NOT_PROBED" : "INVALID");
                })
            .toList());
  }
}

package com.aegisos.boot.config;

import com.aegisos.core.profile.*;
import com.aegisos.provider.config.ProviderRegistry;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

/** 全局连接快照建成后加载 Profile；仅启动/重启读取目录和环境，不注册任何执行器。 */
@Configuration(proxyBeanMethods = false)
public class ProfileConfiguration {
  @Bean
  public ProfileLoadReport profileLoadReport(
      ProviderRegistry providers,
      @Value("${aegisos.workspace-root:#{systemProperties['user.dir']}}") String root) {
    return new ProfileLoader(Path.of(root), providers, System::getenv).load();
  }

  @Bean
  public com.aegisos.core.context.AgentSourceRegistry agentSourceRegistry(
      ProfileLoadReport report) {
    return report.sources();
  }

  @Bean
  public ProfileRegistry profileRegistry(ProfileLoadReport report) {
    return new ProfileRegistry(report.profiles());
  }
}

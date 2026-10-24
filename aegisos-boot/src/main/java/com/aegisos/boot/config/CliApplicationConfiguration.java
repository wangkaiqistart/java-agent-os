package com.aegisos.boot.config;

import com.aegisos.channel.cli.CliChannel;
import com.aegisos.cli.ChatCommand;
import com.aegisos.core.agent.AgentService;
import com.aegisos.core.context.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 专用非 Web source。显式装配，不扫描 Web、自动配置或同类型 Provider Bean。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aegisos.cli.enabled", havingValue = "true")
@Import({
  ProviderConfiguration.class,
  AuditStorageConfiguration.class,
  AgentRuntimeConfiguration.class
})
public class CliApplicationConfiguration {
  @Bean
  public CliChannel cliChannel(SensitiveContentGuard guard, ContextBudget budget) {
    return new CliChannel(guard, budget.maxMessageBytes());
  }

  @Bean
  public ChatCommand chatCommand(AgentService agents, CliChannel channel) {
    return new ChatCommand(agents, channel);
  }
}

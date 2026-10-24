package com.aegisos.boot.config;

import com.aegisos.core.audit.ToolInvocationAuditStore;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.MemoryService;
import com.aegisos.core.tool.*;
import com.aegisos.storage.tool.JpaToolInvocationAuditStore;
import com.aegisos.tool.*;
import jakarta.persistence.EntityManagerFactory;
import java.net.InetAddress;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅生产 http_get 注册；所有执行经过同一个沙箱和持久审计边界。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegisos.react", name = "max-context-bytes")
@EnableConfigurationProperties(SandboxProperties.class)
public class ToolConfiguration {
  @Bean
  public ToolRegistryView toolRegistryView() {
    return new ToolRegistry(List.of(new HttpGetTool()));
  }

  @Bean
  @ConditionalOnMissingBean(Sandbox.class)
  public Sandbox sandbox(SandboxProperties properties, ContextBudget budget) {
    return new WhitelistSandbox(properties.policy(budget), InetAddress::getAllByName);
  }

  @Bean
  public ToolInvocationAuditStore toolInvocationAuditStore(
      EntityManagerFactory emf, PlatformTransactionManager manager) {
    return new JpaToolInvocationAuditStore(emf, manager);
  }

  @Bean
  public ToolExecutor toolExecutor(
      ToolRegistryView registry,
      Sandbox sandbox,
      ToolInvocationAuditStore audit,
      MemoryService memory,
      SensitiveContentGuard guard,
      ContextBudget budget,
      Clock clock) {
    return new DefaultToolExecutor(
        registry,
        new ToolArgumentsParser(budget, guard),
        sandbox,
        audit,
        memory,
        guard,
        budget,
        clock);
  }
}

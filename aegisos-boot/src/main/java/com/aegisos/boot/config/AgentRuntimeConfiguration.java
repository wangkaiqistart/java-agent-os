package com.aegisos.boot.config;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.ProfileRegistry;
import com.aegisos.core.provider.ProfileProviderService;
import com.aegisos.core.tool.*;
import com.aegisos.memory.*;
import com.aegisos.storage.session.JpaSessionStore;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Agent 运行时唯一生产装配入口。预算是显式启用条件，旧 Web 基础启动不会隐式创建会话运行时。 这里显式连接公共端口和后端；core 不依赖 Spring/JPA，也不扫描兄弟模块。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "aegisos.react", name = "max-context-bytes")
@EnableConfigurationProperties(ReactProperties.class)
@Import(ToolConfiguration.class)
public class AgentRuntimeConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public Clock runtimeClock() {
    return Clock.systemUTC();
  }

  @Bean
  public ContextBudget contextBudget(ReactProperties properties) {
    return properties.budget();
  }

  @Bean
  public SensitiveContentGuard sensitiveContentGuard(RuntimeSecretSnapshot snapshot) {
    return snapshot.guard();
  }

  @Bean
  public com.aegisos.storage.runtime.RuntimeRecoveryService runtimeRecoveryService(
      EntityManagerFactory emf, PlatformTransactionManager manager, Clock clock) {
    return new com.aegisos.storage.runtime.RuntimeRecoveryService(emf, manager, clock);
  }

  @Bean
  @DependsOn("runtimeRecoveryService")
  public SessionStore sessionStore(
      EntityManagerFactory emf, PlatformTransactionManager manager, Clock clock) {
    return new JpaSessionStore(emf, manager, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public LongTermMemoryStore longTermMemoryStore() {
    return new EmptyLongTermMemoryStore();
  }

  @Bean
  public MemoryService memoryService(
      SessionStore store,
      LongTermMemoryStore longTerm,
      SensitiveContentGuard guard,
      ContextBudget budget) {
    return new DefaultMemoryService(store, longTerm, guard, budget);
  }

  @Bean
  public ContextLoader contextLoader(ContextBudget budget, SensitiveContentGuard guard) {
    return new ContextLoader(budget, guard);
  }

  @Bean
  public PromptBuilder promptBuilder(
      AgentSourceRegistry sources,
      ContextLoader loader,
      MemoryService memory,
      ToolRegistryView tools,
      ContextBudget budget,
      SensitiveContentGuard guard,
      Clock clock,
      ReactProperties properties) {
    return new PromptBuilder(
        sources, loader, memory, tools, budget, guard, clock, properties.zoneId());
  }

  @Bean
  public ReActLoop reActLoop(
      ProfileProviderService provider,
      MemoryService memory,
      PromptBuilder prompts,
      ToolExecutor executor,
      ContextBudget budget,
      SensitiveContentGuard guard) {
    return new ReActLoop(provider, memory, prompts, executor, budget, guard);
  }

  @Bean
  public AgentService agentService(
      ProfileRegistry profiles, MemoryService memory, ReActLoop loop, SensitiveContentGuard guard) {
    return new AgentService(profiles, memory, loop, guard, UUID.randomUUID());
  }
}

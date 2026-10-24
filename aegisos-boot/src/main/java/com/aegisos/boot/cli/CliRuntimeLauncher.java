package com.aegisos.boot.cli;

import com.aegisos.cli.CliArguments;
import java.io.*;
import org.springframework.boot.SpringApplication;

/** 运行时生命周期唯一所有者；所有退出路径由try-with-resources关闭客户端、池及自有锁。 */
public final class CliRuntimeLauncher {
  @FunctionalInterface
  public interface TerminalDetector {
    boolean isTerminal();
  }

  private final TerminalDetector terminal;

  public CliRuntimeLauncher() {
    this(() -> System.console() != null);
  }

  public CliRuntimeLauncher(TerminalDetector terminal) {
    this.terminal = terminal;
  }

  public int chat(CliArguments parsed, InputStream in, PrintStream out, PrintStream err) {
    var values =
        new java.util.HashMap<String, Object>(
            new com.aegisos.boot.config.CliWorkspaceConfiguration().load(parsed.workspace()));
    values.put("aegisos.cli.enabled", "true");
    values.put("spring.config.location", "classpath:/cli-empty.yml");
    values.put("logging.config", "classpath:logback-cli.xml");
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment
        .getPropertySources()
        .remove(
            org.springframework.core.env.StandardEnvironment
                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(
            org.springframework.core.env.StandardEnvironment
                .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource("approved-cli-workspace", values));
    var application =
        new SpringApplication(com.aegisos.boot.config.CliApplicationConfiguration.class);
    application.setEnvironment(environment);
    application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
    application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
    application.setLogStartupInfo(false);
    application.setAddCommandLineProperties(false);
    application.setRegisterShutdownHook(true);
    // 在打开任何运行时资源之前确定模式，无法确认终端时保守使用批处理。
    boolean interactive = parsed.interactive(terminal.isTerminal());
    try (var context = application.run()) {
      var chat = context.getBean(com.aegisos.cli.ChatCommand.class);
      return interactive
          ? chat.runInteractive(
              parsed.agent(), parsed.session(), parsed.options().get("user"), in, out, err)
          : chat.run(parsed.agent(), parsed.session(), in, out);
    }
  }

  public org.springframework.context.ConfigurableApplicationContext openServe(CliArguments parsed) {
    var values =
        new java.util.HashMap<String, Object>(
            new com.aegisos.boot.config.CliWorkspaceConfiguration().load(parsed.workspace()));
    values.put("aegisos.serve.enabled", "true");
    values.put("spring.config.location", "classpath:/cli-empty.yml");
    values.put("logging.config", "classpath:logback-cli.xml");
    values.put("server.address", "127.0.0.1");
    values.put("server.port", Integer.parseInt(parsed.options().getOrDefault("port", "8080")));
    values.put("management.endpoints.web.exposure.include", "health");
    values.put("management.endpoint.health.show-details", "never");
    values.put("management.endpoint.health.show-components", "never");
    values.put("management.endpoint.health.probes.enabled", "false");
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment
        .getPropertySources()
        .remove(
            org.springframework.core.env.StandardEnvironment
                .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(
            org.springframework.core.env.StandardEnvironment
                .SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource("approved-serve-workspace", values));
    var app = new SpringApplication(com.aegisos.boot.config.ServeApplicationConfiguration.class);
    app.setEnvironment(environment);
    app.setWebApplicationType(org.springframework.boot.WebApplicationType.SERVLET);
    app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
    app.setLogStartupInfo(false);
    app.setAddCommandLineProperties(false);
    return app.run();
  }

  public int serve(CliArguments parsed, PrintStream out) {
    var stopped = new java.util.concurrent.CountDownLatch(1);
    try (var context = openServe(parsed)) {
      context.addApplicationListener(
          event -> {
            if (event instanceof org.springframework.context.event.ContextClosedEvent)
              stopped.countDown();
          });
      out.println(
          "{\"status\":\"SERVING\",\"capability\":\"WEB_BASELINE_ONLY\",\"address\":\"127.0.0.1\"}");
      if (out.checkError()) return 5;
      // 仅Web基础：无Agent业务接口，无模型/工具调用，终止时关闭池和自有锁。
      try {
        if (context.isActive()) stopped.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return 5;
      }
      return 0;
    }
  }
}

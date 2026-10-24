package com.aegisos.cli.command;

import java.util.*;
import picocli.CommandLine.Option;

/** 命令的受限控制参数；只允许各叶命令白名单字段，不接受消息或密钥。 */
public class CommandOptions {
  @Option(names = "--workspace")
  public String workspace;

  @Option(names = "--profile")
  public String profile;

  @Option(names = "--agent")
  public String agent;

  @Option(names = "--session")
  public String session;

  @Option(names = "--interactive")
  public boolean interactive;

  @Option(names = "--batch")
  public boolean batch;

  @Option(names = "--user")
  public String user;

  @Option(names = "--name")
  public String name;

  @Option(names = "--provider")
  public String provider;

  @Option(names = "--model")
  public String model;

  @Option(names = "--limit")
  public String limit;

  @Option(names = "--offset")
  public String offset;

  @Option(names = "--port")
  public String port;

  /** 模型和领域验证共享同一清单，避免help承诺一个实际拒绝的选项。 */
  public static Set<String> allowed(String command) {
    return switch (command) {
      case "init", "status", "gateway", "profile list", "provider list", "tool list" ->
          Set.of("workspace");
      case "chat" ->
          Set.of("workspace", "profile", "agent", "session", "interactive", "batch", "user");
      case "serve" -> Set.of("workspace", "port");
      case "profile show", "profile delete" -> Set.of("workspace", "name");
      case "profile create" -> Set.of("workspace", "name", "provider", "model");
      case "session list" -> Set.of("workspace", "profile", "limit", "offset");
      default -> throw new IllegalArgumentException("INVALID_CLI_ARGUMENTS");
    };
  }
}

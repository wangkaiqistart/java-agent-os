package com.aegisos.cli;

import com.aegisos.cli.command.*;
import java.io.PrintStream;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;

/** 只构造命令模型；解析和帮助绝不创建Spring/数据库/网络客户端。 */
@Command(
    name = "aegisos",
    description = "AegisOS Agent运行时",
    mixinStandardHelpOptions = true,
    version = "AegisOS 0.1.0-SNAPSHOT")
public final class AegisOsCli {
  public static CommandLine commandLine() {
    var root = new CommandLine(new AegisOsCli());
    root.addSubcommand(new InitCliCommand())
        .addSubcommand(new StatusCommand())
        .addSubcommand(new ChatCliCommand())
        .addSubcommand(new ServeCommand())
        .addSubcommand(new GatewayCommand());
    var profile =
        new CommandLine(CommandSpec.create().name("profile").mixinStandardHelpOptions(true));
    profile
        .addSubcommand(new ProfileListCommand())
        .addSubcommand(new ProfileCreateCommand())
        .addSubcommand(new ProfileShowCommand())
        .addSubcommand(new ProfileDeleteCommand());
    root.addSubcommand(profile);
    for (var entry :
        java.util.Map.of(
                "provider",
                new ProviderListCommand(),
                "tool",
                new ToolListCommand(),
                "session",
                new SessionListCommand())
            .entrySet()) {
      var group =
          new CommandLine(CommandSpec.create().name(entry.getKey()).mixinStandardHelpOptions(true));
      group.addSubcommand(entry.getValue());
      root.addSubcommand(group);
    }
    configure(root, "");
    root.setOverwrittenOptionsAllowed(false);
    root.setUnmatchedArgumentsAllowed(false);
    return root;
  }

  private static void configure(CommandLine command, String path) {
    if (command.getCommandSpec().userObject() instanceof CommandOptions) {
      var spec = command.getCommandSpec();
      var allowed = CommandOptions.allowed(path);
      for (var option : new java.util.ArrayList<>(spec.options())) {
        String key = option.longestName().substring(2);
        if (java.util.Set.of("help", "version").contains(key)) continue;
        spec.remove(option);
        if (allowed.contains(key)) {
          boolean required =
              key.equals("workspace")
                  || key.equals("name")
                  || path.equals("profile create")
                      && java.util.Set.of("provider", "model").contains(key);
          spec.addOption(option.toBuilder().required(required).build());
        }
      }
      if (path.equals("chat")) {
        var profile = spec.findOption("--profile");
        var agent = spec.findOption("--agent");
        spec.remove(profile);
        spec.remove(agent);
        spec.addArgGroup(
            picocli.CommandLine.Model.ArgGroupSpec.builder()
                .exclusive(true)
                .multiplicity("1")
                .addArg(profile)
                .addArg(agent)
                .build());
      }
    }
    for (var entry : command.getSubcommands().entrySet())
      configure(entry.getValue(), path.isEmpty() ? entry.getKey() : path + " " + entry.getKey());
  }

  public static void printHelp(String path, PrintStream out) {
    CommandLine command = commandLine();
    if (path != null && !path.isBlank())
      for (String word : path.split(" ")) command = command.getSubcommands().get(word);
    command.usage(out);
  }
}

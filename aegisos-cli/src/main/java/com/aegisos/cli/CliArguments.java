package com.aegisos.cli;

import com.aegisos.cli.command.CommandOptions;
import com.aegisos.core.provider.SessionAssociation;
import java.nio.file.Path;
import java.util.*;

/** Picocli负责结构，领域白名单负责语义；所有异常仅保留固定代码，绝不回显原始参数。 */
public record CliArguments(
    String command, Path workspace, String agent, String session, Map<String, String> options) {
  public CliArguments {
    options = Map.copyOf(options);
  }

  public boolean interactive(boolean terminal) {
    boolean selected =
        options.containsKey("interactive") || !options.containsKey("batch") && terminal;
    if (!selected && options.containsKey("user")) throw invalid();
    return selected;
  }

  public static CliArguments parse(String[] args) {
    try {
      var root = AegisOsCli.commandLine();
      var result = root.parseArgs(args);
      var leaf = result;
      var words = new ArrayList<String>();
      while (leaf.hasSubcommand()) {
        leaf = leaf.subcommand();
        words.add(leaf.commandSpec().name());
      }
      String command = String.join(" ", words);
      if (result.isUsageHelpRequested() || leaf.isUsageHelpRequested())
        return new CliArguments("help", null, null, null, Map.of("help-path", command));
      if (result.isVersionHelpRequested() || leaf.isVersionHelpRequested())
        return new CliArguments("version", null, null, null, Map.of());
      if (!(leaf.commandSpec().userObject() instanceof CommandOptions value)) throw invalid();
      Set<String> allowed = CommandOptions.allowed(command);
      var options = new LinkedHashMap<String, String>();
      for (var option : leaf.matchedOptions()) {
        String key = option.longestName().substring(2);
        if (!allowed.contains(key)) throw invalid();
        Object raw = option.getValue();
        options.put(key, String.valueOf(raw));
      }
      if (value.workspace == null) throw invalid();
      Path path = Path.of(value.workspace);
      if (!path.isAbsolute()) throw invalid();
      if (value.profile != null && value.agent != null || value.interactive && value.batch)
        throw invalid();
      String profile = value.profile != null ? value.profile : value.agent;
      if (profile != null) name(profile);
      if (value.session != null && SessionAssociation.safe(value.session) == null) throw invalid();
      if (value.user != null
          && (!value.user.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
              || value.batch
              || value.session != null)) throw invalid();
      if (command.equals("chat") && profile == null) throw invalid();
      if (Set.of("profile create", "profile show", "profile delete").contains(command))
        name(value.name);
      if (command.equals("profile create")) {
        name(value.provider);
        if (com.aegisos.core.provider.SafeProviderMetadata.model(value.model) == null)
          throw invalid();
      }
      integer(value.limit, 1, 100);
      integer(value.offset, 0, 100000);
      integer(value.port, 1, 65535);
      return new CliArguments(command, path.normalize(), profile, value.session, options);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static void name(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid();
  }

  private static void integer(String value, int min, int max) {
    if (value != null) {
      int n = Integer.parseInt(value);
      if (!value.matches("[0-9]+") || n < min || n > max) throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_CLI_ARGUMENTS");
  }
}

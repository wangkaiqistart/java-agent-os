package com.aegisos.cli;

import com.aegisos.core.provider.SessionAssociation;
import java.nio.file.*;
import java.util.*;

/** 固定命令解析器；参数只承载控制信息，正文只能经 stdin，错误不回显参数。 */
public record CliArguments(String command, Path workspace, String agent, String session) {
  public static CliArguments parse(String[] args) {
    try {
      if (args.length < 3 || !Set.of("init", "chat").contains(args[0]) || args.length % 2 == 0)
        throw invalid();
      var values = new HashMap<String, String>();
      var allowed =
          args[0].equals("init")
              ? Set.of("--workspace")
              : Set.of("--workspace", "--agent", "--session");
      for (int i = 1; i < args.length; i += 2)
        if (!allowed.contains(args[i])
            || values.putIfAbsent(args[i], args[i + 1]) != null
            || args[i + 1].startsWith("--")) throw invalid();
      Path workspace = Path.of(Objects.requireNonNull(values.get("--workspace")));
      String agent = values.get("--agent"), session = values.get("--session");
      if (!workspace.isAbsolute()
          || (args[0].equals("chat")
              && (agent == null || !agent.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
          || (session != null && SessionAssociation.safe(session) == null)) throw invalid();
      return new CliArguments(args[0], workspace.normalize(), agent, session);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_CLI_ARGUMENTS");
  }
}

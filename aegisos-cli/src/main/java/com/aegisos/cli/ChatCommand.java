package com.aegisos.cli;

import com.aegisos.channel.cli.CliInputOutput;
import com.aegisos.core.agent.*;
import java.io.*;

/** 只调用统一 AgentService；先验输入再新建会话，不为任何失败隐式重发。 */
public final class ChatCommand {
  private final AgentService agents;
  private final java.util.function.Function<InputStream, String> readLine;
  private final java.util.function.BiConsumer<ProcessingResult, PrintStream> writeInteractive;
  private final java.util.function.Function<InputStream, String> readInput;
  private final java.util.function.BiConsumer<ProcessingResult, PrintStream> writeResult;

  public ChatCommand(AgentService agents, CliInputOutput channel) {
    this(agents, channel, null);
  }

  public ChatCommand(
      AgentService agents, CliInputOutput channel, com.aegisos.channel.cli.ReplChannel repl) {
    this.agents = agents;
    // 与batch渠道相同，只保存受限读/写行为，不保留具体渠道对象或暴露其内部状态。
    readLine = repl == null ? null : repl::readLine;
    writeInteractive = repl == null ? null : repl::write;
    // 命令只保存两个受限操作，不保留/暴露渠道配置或其生命周期。
    readInput = channel::read;
    writeResult = channel::write;
  }

  public int run(String profile, String session, InputStream in, PrintStream out) {
    ProcessingResult result;
    try {
      String text = readInput.apply(in);
      String selected = session == null ? agents.createSession(profile).sessionId() : session;
      result = agents.process(new AgentRequest(selected, profile, text));
    } catch (AgentException e) {
      result = new ProcessingResult(null, null, ProcessingStatus.FAILED, 0, null, e.code(), false);
    } catch (RuntimeException e) {
      result =
          new ProcessingResult(
              null,
              null,
              ProcessingStatus.FAILED,
              0,
              null,
              AgentErrorCode.PERSISTENCE_UNCONFIRMED,
              false);
    }
    try {
      writeResult.accept(result, out);
      return out.checkError() ? 5 : exitCode(result);
    } catch (RuntimeException e) {
      return 5;
    }
  }

  public int runInteractive(
      String profile,
      String session,
      String user,
      InputStream in,
      PrintStream out,
      PrintStream err) {
    if (readLine == null || writeInteractive == null) return 5;
    String selected = session;
    try {
      while (true) {
        if (Thread.currentThread().isInterrupted()) return 5;
        err.print("> ");
        err.flush();
        if (err.checkError()) return 5;
        String text = readLine.apply(in);
        if (text == null || text.strip().equals("/quit")) return 0;
        if (text.isBlank()) continue;
        // 第一次有效输入才创建绑定；控制行、空白和EOF都不制造空会话。
        if (selected == null)
          selected =
              agents
                  .getOrCreateSession(
                      new com.aegisos.core.memory.SessionIdentity(
                          "cli", user == null ? "local" : user, profile))
                  .sessionId();
        var result = agents.process(new AgentRequest(selected, profile, text));
        writeInteractive.accept(result, out);
        int exit = exitCode(result);
        if (exit != 0) return exit;
      }
    } catch (AgentException e) {
      err.println(e.code().name());
      return exitCode(
          new ProcessingResult(null, null, ProcessingStatus.FAILED, 0, null, e.code(), false));
    } catch (RuntimeException e) {
      err.println("CLI_PROCESSING_FAILED");
      return 5;
    }
  }

  public static int exitCode(ProcessingResult result) {
    if (result.status() == ProcessingStatus.COMPLETED && result.persistenceConfirmed()) return 0;
    if (result.errorCode() == null) return 5;
    return switch (result.errorCode()) {
      case INVALID_INPUT,
              UNKNOWN_PROFILE,
              UNKNOWN_SESSION,
              SESSION_PROFILE_MISMATCH,
              SENSITIVE_CONTENT,
              CONTEXT_LIMIT,
              INVALID_ARGUMENTS ->
          2;
      case SESSION_BUSY, WORKSPACE_IN_USE -> 3;
      case MAX_ITERATIONS -> 4;
      default -> 5;
    };
  }
}

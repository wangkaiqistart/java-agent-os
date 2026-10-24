package com.aegisos.cli;

import com.aegisos.channel.cli.CliInputOutput;
import com.aegisos.core.agent.*;
import java.io.*;

/** 只调用统一 AgentService；先验输入再新建会话，不为任何失败隐式重发。 */
public final class ChatCommand {
  private final AgentService agents;
  private final java.util.function.Function<InputStream, String> readInput;
  private final java.util.function.BiConsumer<ProcessingResult, PrintStream> writeResult;

  public ChatCommand(AgentService agents, CliInputOutput channel) {
    this.agents = agents;
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
    writeResult.accept(result, out);
    return exitCode(result);
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

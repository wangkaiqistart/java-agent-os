package com.aegisos.channel.cli;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** 单次有界 stdin / 固定七字段 JSON；异常和输入不进入诊断输出。 */
public final class CliChannel implements CliInputOutput {
  private final SensitiveContentGuard guard;
  private final int maxBytes;

  public CliChannel(SensitiveContentGuard guard, int maxBytes) {
    if (maxBytes < 1 || maxBytes > 1048576)
      throw new IllegalArgumentException("INVALID_INPUT_LIMIT");
    this.guard = guard;
    this.maxBytes = maxBytes;
  }

  public String read(InputStream in) {
    try {
      byte[] bytes = in.readNBytes(maxBytes + 1);
      if (bytes.length > maxBytes) throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (value.isBlank()) throw new AgentException(AgentErrorCode.INVALID_INPUT);
      guard.check(value);
      return value;
    } catch (IOException e) {
      throw new AgentException(AgentErrorCode.INVALID_INPUT);
    }
  }

  public void write(ProcessingResult result, PrintStream out) {
    // 先检查所有可变字段并完整构造，再一次输出，避免检查失败时留下半段正文。
    guard.check(result.sessionId());
    guard.check(result.reply());
    out.println(
        "{\"sessionId\":"
            + ContextSizer.quote(result.sessionId())
            + ",\"processId\":"
            + ContextSizer.quote(result.processId() == null ? null : result.processId().toString())
            + ",\"status\":"
            + ContextSizer.quote(result.status().name())
            + ",\"iterationCount\":"
            + result.iterationCount()
            + ",\"reply\":"
            + ContextSizer.quote(result.reply())
            + ",\"error\":"
            + ContextSizer.quote(result.errorCode() == null ? null : result.errorCode().name())
            + ",\"persistenceConfirmed\":"
            + result.persistenceConfirmed()
            + "}");
  }
}

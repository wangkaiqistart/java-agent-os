package com.aegisos.channel.cli;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.SensitiveContentGuard;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Objects;

/** 字节预算约束每行；检查完成后才交出正文，绝不回显原始输入。 */
public final class ReplChannel {
  private final SensitiveContentGuard guard;
  private final int maxBytes;

  public ReplChannel(SensitiveContentGuard guard, int maxBytes) {
    if (maxBytes < 1 || maxBytes > 1048576)
      throw new IllegalArgumentException("INVALID_INPUT_LIMIT");
    this.guard = Objects.requireNonNull(guard);
    this.maxBytes = maxBytes;
  }

  public String readLine(InputStream input) {
    try {
      var bytes = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
      int next;
      // 多容纳一个可能属于 CRLF 的 CR，除此之外不读取或缓存无界正文。
      while ((next = input.read()) != -1 && next != '\n') {
        if (bytes.size() >= maxBytes + 1) throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
        bytes.write(next);
      }
      if (next == -1 && bytes.size() == 0) return null;
      byte[] value = bytes.toByteArray();
      int length = value.length;
      if (next == '\n' && length > 0 && value[length - 1] == '\r') length--;
      if (length > maxBytes) throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(value, 0, length))
              .toString();
      guard.check(text);
      return text;
    } catch (IOException e) {
      throw new AgentException(AgentErrorCode.INVALID_INPUT);
    }
  }

  public void write(ProcessingResult result, PrintStream out) {
    guard.check(result.sessionId());
    guard.check(result.reply());
    String text =
        "session: "
            + result.sessionId()
            + "\n"
            + (result.errorCode() == null ? result.reply() : result.errorCode().name());
    guard.check(text);
    out.println(text);
    if (out.checkError()) throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
  }
}

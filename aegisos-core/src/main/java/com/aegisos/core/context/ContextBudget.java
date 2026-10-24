package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;

/** 容量均指规范 JSON/文件的 UTF-8 字节，不是 Java 字符数或模型 token。 */
public record ContextBudget(
    int maxContextBytes,
    int maxMessageBytes,
    int maxContextFileBytes,
    int maxContextFiles,
    int maxToolArgumentsBytes,
    int maxToolResultBytes,
    int maxToolsPerResponse,
    int maxOutputTokens) {
  public ContextBudget {
    if (maxContextBytes < 4096
        || maxContextBytes > 1048576
        || maxMessageBytes < 1
        || maxMessageBytes > maxContextBytes
        || maxContextFileBytes < 1
        || maxContextFileBytes > maxContextBytes
        || maxContextFiles < 1
        || maxContextFiles > 64
        || maxToolArgumentsBytes < 1
        || maxToolArgumentsBytes > 32768
        || maxToolResultBytes < 1
        || maxToolResultBytes > maxContextBytes
        || maxToolsPerResponse < 1
        || maxToolsPerResponse > 32
        || maxOutputTokens < 1
        || maxOutputTokens > 8192) throw new IllegalArgumentException("INVALID_REACT_BUDGET");
  }

  public void check(ProfilePrompt prompt) {
    for (var message : prompt.messages())
      if (ContextSizer.messageBytes(message) > maxMessageBytes)
        throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    if (ContextSizer.bytes(prompt) > maxContextBytes)
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
  }
}

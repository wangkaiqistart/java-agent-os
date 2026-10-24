package com.aegisos.core.memory;

/** 只有经过敏感校验的可选召回可因容量被移除；由Prompt边界记录固定裁剪元数据。 */
public final class LongTermMemoryLimitException extends RuntimeException {
  public LongTermMemoryLimitException() {
    super("OPTIONAL_MEMORY_LIMIT", null, false, false);
  }
}

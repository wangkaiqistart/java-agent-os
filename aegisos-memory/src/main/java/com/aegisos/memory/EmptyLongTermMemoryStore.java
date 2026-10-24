package com.aegisos.memory;

import com.aegisos.core.memory.*;

/** US2 默认没有长期后端；不会借空召回触发文件读取、数据库扫描或外部模型。 */
public final class EmptyLongTermMemoryStore implements LongTermMemoryStore {
  public java.util.Optional<String> recall(MemoryQuery query) {
    java.util.Objects.requireNonNull(query);
    return java.util.Optional.empty();
  }
}

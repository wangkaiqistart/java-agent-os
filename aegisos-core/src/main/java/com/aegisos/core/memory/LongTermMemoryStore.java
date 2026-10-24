package com.aegisos.core.memory;

public interface LongTermMemoryStore {
  java.util.Optional<String> recall(MemoryQuery query);
}

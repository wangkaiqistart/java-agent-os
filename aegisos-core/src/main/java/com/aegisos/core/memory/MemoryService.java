package com.aegisos.core.memory;

/** ReAct 访问会话与长期记忆的唯一门面；禁止编排依赖具体持久化实现。 */
public interface MemoryService extends SessionStore {
  java.util.Optional<String> recallLongTerm(MemoryQuery query);
}

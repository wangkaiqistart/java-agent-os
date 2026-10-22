package com.aegisos.core.provider;

import java.util.*;

/** 调用方只提供消息、schema 和输出上限，不能覆盖 Profile 的 Provider/模型/温度。 */
public record ProfilePrompt(
    List<ModelMessage> messages, List<ToolSchema> toolSchemas, Integer maxOutputTokens) {
  public ProfilePrompt {
    messages = messages == null ? null : Collections.unmodifiableList(new ArrayList<>(messages));
    toolSchemas =
        toolSchemas == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(toolSchemas));
  }

  /** 防御性返回 schema 列表；非法元素仍交由统一请求校验拒绝。 */
  public List<ToolSchema> toolSchemas() {
    return Collections.unmodifiableList(new ArrayList<>(toolSchemas));
  }

  public static ProfilePrompt text(String text) {
    return new ProfilePrompt(
        List.of(new ModelMessage(ModelMessage.Role.USER, text, null, List.of())), List.of(), null);
  }

  @Override
  public String toString() {
    return "ProfilePrompt[redacted]";
  }
}

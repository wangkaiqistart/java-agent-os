package com.aegisos.core.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 无效请求也交由 ProviderService 生成 requestId 并审计。 */
public record ModelRequest(
    String providerName,
    String modelName,
    List<ModelMessage> messages,
    List<ToolSchema> toolSchemas,
    GenerationOptions generationOptions) {
  public ModelRequest {
    messages = messages == null ? null : Collections.unmodifiableList(new ArrayList<>(messages));
    toolSchemas =
        toolSchemas == null ? null : Collections.unmodifiableList(new ArrayList<>(toolSchemas));
  }

  public static ModelRequest text(String provider, String model, String text) {
    return new ModelRequest(
        provider,
        model,
        List.of(new ModelMessage(ModelMessage.Role.USER, text, null, List.of())),
        List.of(),
        null);
  }

  @Override
  public String toString() {
    return "ModelRequest[redacted]";
  }
}

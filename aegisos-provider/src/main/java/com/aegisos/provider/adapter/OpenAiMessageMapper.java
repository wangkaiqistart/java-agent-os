package com.aegisos.provider.adapter;

import com.aegisos.core.provider.*;
import com.aegisos.provider.config.ProviderThinkingMode;
import com.aegisos.provider.service.ProviderErrorClassifier;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

public final class OpenAiMessageMapper {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private OpenAiMessageMapper() {}

  private static void invalid() {
    throw ProviderErrorClassifier.error(
        ProviderErrorCode.INVALID_REQUEST, false, ExternalOutcome.NOT_SENT);
  }

  private static void schemaError() {
    throw ProviderErrorClassifier.error(
        ProviderErrorCode.UNSUPPORTED_TOOL_SCHEMA, false, ExternalOutcome.NOT_SENT);
  }

  public static void validate(ModelRequest r) {
    if (r.messages() == null || r.messages().isEmpty()) invalid();
    var priorCalls = new HashMap<String, String>();
    for (var m : r.messages()) {
      if (m == null || m.role() == null) invalid();
      if (m.role() != ModelMessage.Role.ASSISTANT && m.content() == null) invalid();
      var calls = m.toolCalls() == null ? List.<ToolCall>of() : m.toolCalls();
      if (m.role() != ModelMessage.Role.ASSISTANT && !calls.isEmpty()) invalid();
      if (m.role() == ModelMessage.Role.ASSISTANT
          && (m.content() == null || m.content().isEmpty())
          && calls.isEmpty()) invalid();
      for (var call : calls) {
        if (!validCall(call) || priorCalls.putIfAbsent(call.id(), call.name()) != null) invalid();
      }
      if (m.role() == ModelMessage.Role.TOOL
          && (m.toolCallId() == null || !priorCalls.containsKey(m.toolCallId()))) invalid();
      if (m.role() != ModelMessage.Role.TOOL && m.toolCallId() != null) invalid();
    }
    // Spring AI 2.0.1 直接 model.call 不执行 ToolCallback；没有旧版 internalToolExecutionEnabled API。
    // strict(false) 防止 SDK 为严格模式补写 required/additionalProperties，保持参数 schema 原意。
    var options = r.generationOptions();
    if (options != null
        && ((options.temperature() != null
                && (!Double.isFinite(options.temperature())
                    || options.temperature() < 0
                    || options.temperature() > 2))
            || (options.maxOutputTokens() != null && options.maxOutputTokens() <= 0))) invalid();
    var names = new HashSet<String>();
    if (r.toolSchemas() != null)
      for (var s : r.toolSchemas()) {
        if (s == null
            || s.name() == null
            || !s.name().matches("[A-Za-z0-9_-]{1,64}")
            || !names.add(s.name())) schemaError();
        ToolSchemaValidator.validate(s.inputSchemaJson());
      }
  }

  private static boolean validCall(ToolCall c) {
    if (c == null
        || c.id() == null
        || c.id().isBlank()
        || c.name() == null
        || !c.name().matches("[A-Za-z0-9_-]{1,64}")) return false;
    try {
      var n = JSON.readTree(c.argumentsJson());
      return n != null && n.isObject();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public static Prompt prompt(ModelRequest r) {
    return prompt(r, ProviderThinkingMode.UNSPECIFIED);
  }

  /** 每轮都由生产映射器使用启动快照生成扩展，避免续轮丢失或被缺省请求选项覆盖。 */
  public static Prompt prompt(ModelRequest r, ProviderThinkingMode thinkingMode) {
    if (thinkingMode == null) invalid();
    validate(r);
    var messages = new ArrayList<Message>();
    var callNames = new HashMap<String, String>();
    for (var m : r.messages())
      switch (m.role()) {
        case SYSTEM -> messages.add(new SystemMessage(m.content()));
        case USER -> messages.add(new UserMessage(m.content()));
        case ASSISTANT -> {
          var calls = m.toolCalls() == null ? List.<ToolCall>of() : m.toolCalls();
          calls.forEach(c -> callNames.put(c.id(), c.name()));
          messages.add(
              AssistantMessage.builder()
                  .content(m.content())
                  .toolCalls(
                      calls.stream()
                          .map(
                              c ->
                                  new AssistantMessage.ToolCall(
                                      c.id(), "function", c.name(), c.argumentsJson()))
                          .toList())
                  .build());
        }
        case TOOL ->
            messages.add(
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(
                                m.toolCallId(), callNames.get(m.toolCallId()), m.content())))
                    .build());
      }
    // Spring AI 2.0.1 直接 model.call 不执行 ToolCallback；没有旧版 internalToolExecutionEnabled API。
    // strict(false) 防止 SDK 为严格模式补写 required/additionalProperties，保持参数 schema 原意。
    var options = OpenAiChatOptions.builder().model(r.modelName()).maxRetries(0).strict(false);
    if (thinkingMode == ProviderThinkingMode.DISABLED)
      options.extraBody(Map.of("thinking", Map.of("type", "disabled")));
    if (r.generationOptions() != null) {
      options.temperature(r.generationOptions().temperature());
      options.maxCompletionTokens(r.generationOptions().maxOutputTokens());
    }
    if (r.toolSchemas() != null)
      options.toolCallbacks(
          r.toolSchemas().stream()
              .map(SchemaDescriptor::new)
              .map(ToolCallback.class::cast)
              .toList());
    return new Prompt(messages, options.build()) {
      @Override
      public String toString() {
        return "Prompt[redacted]";
      }
    };
  }

  public static ModelResponse response(ModelRequest r, ChatResponse response, TokenUsage usage) {
    var g = response == null ? null : response.getResult();
    var output = g == null ? null : g.getOutput();
    if (g == null || output == null)
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
    String content = output.getText();
    var calls =
        output.getToolCalls().stream()
            .map(c -> new ToolCall(c.id(), c.name(), c.arguments()))
            .toList();
    if (calls.stream().anyMatch(c -> !validCall(c))
        || ((content == null || content.isEmpty()) && calls.isEmpty()))
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
    return new ModelResponse(
        null,
        r.providerName(),
        r.modelName(),
        content,
        calls,
        g.getMetadata().getFinishReason(),
        usage);
  }

  /** Spring AI 2 的 schema 元数据接口；没有执行体，即使未来误接 Advisor 也立即失败。 */
  private record SchemaDescriptor(ToolSchema schema) implements ToolCallback {
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(schema.name())
          .description(schema.description() == null ? "" : schema.description())
          .inputSchema(schema.inputSchemaJson())
          .build();
    }

    public String call(String ignored) {
      throw new IllegalStateException("Tool execution forbidden in Provider");
    }

    @Override
    public String toString() {
      return "SchemaDescriptor[redacted]";
    }
  }
}

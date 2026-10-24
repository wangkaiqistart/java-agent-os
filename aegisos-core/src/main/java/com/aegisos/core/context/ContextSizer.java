package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 确定性逻辑负载计量。固定键顺序、显式 null、控制字符小写 unicode 转义；不落盘正文。 */
public final class ContextSizer {
  private ContextSizer() {}

  public static String canonical(ProfilePrompt p) {
    return "{\"messages\":"
        + array(p.messages(), ContextSizer::message)
        + ",\"toolSchemas\":"
        + array(
            p.toolSchemas(),
            s ->
                "{\"name\":"
                    + quote(s.name())
                    + ",\"description\":"
                    + quote(s.description())
                    + ",\"inputSchemaJson\":"
                    + quote(s.inputSchemaJson())
                    + "}")
        + ",\"maxOutputTokens\":"
        + p.maxOutputTokens()
        + "}";
  }

  public static int bytes(ProfilePrompt p) {
    return utf8(canonical(p));
  }

  public static int messageBytes(ModelMessage m) {
    return utf8(message(m));
  }

  public static int utf8(String s) {
    if (s == null) return 0;
    try {
      return StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(s))
          .remaining();
    } catch (CharacterCodingException e) {
      throw new AgentException(AgentErrorCode.INVALID_INPUT);
    }
  }

  public static String quote(String s) {
    if (s == null) return "null";
    utf8(s);
    var out = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') out.append('\\').append(c);
      else if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      else out.append(c);
    }
    return out.append('"').toString();
  }

  private static String message(ModelMessage m) {
    return "{\"role\":"
        + quote(m.role() == null ? null : m.role().name())
        + ",\"content\":"
        + quote(m.content())
        + ",\"toolCallId\":"
        + quote(m.toolCallId())
        + ",\"toolCalls\":"
        + array(
            m.toolCalls(),
            c ->
                "{\"id\":"
                    + quote(c.id())
                    + ",\"name\":"
                    + quote(c.name())
                    + ",\"argumentsJson\":"
                    + quote(c.argumentsJson())
                    + "}")
        + "}";
  }

  private static <T> String array(List<T> list, Function<T, String> writer) {
    return list.stream().map(writer).collect(Collectors.joining(",", "[", "]"));
  }
}

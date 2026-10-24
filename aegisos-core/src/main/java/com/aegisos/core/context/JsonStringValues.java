package com.aegisos.core.context;

import com.aegisos.core.agent.*;

/**
 * 扫描 JSON 字符串词法值及相邻的字段/标量关系，防止标准转义隐藏已知凭据或敏感字段名。 对象形状、重复键、深度等仍由 Provider/Tool 的正式 JSON
 * 解析器校验；这里不递归解析字符串内嵌的 JSON，也不替代业务解析器。
 */
final class JsonStringValues {
  private JsonStringValues() {}

  static void check(String json, SensitiveContentGuard guard) {
    if (json == null) return;
    String field = null;
    for (int i = 0; i < json.length(); i++) {
      if (json.charAt(i) != '"') continue;
      var value = new StringBuilder();
      boolean closed = false;
      while (++i < json.length()) {
        char c = json.charAt(i);
        if (c == '"') {
          closed = true;
          break;
        }
        if (c < 32) throw invalid();
        if (c != '\\') {
          value.append(c);
          continue;
        }
        if (++i >= json.length()) throw invalid();
        char escape = json.charAt(i);
        switch (escape) {
          case '"', '\\', '/' -> value.append(escape);
          case 'b' -> value.append('\b');
          case 'f' -> value.append('\f');
          case 'n' -> value.append('\n');
          case 'r' -> value.append('\r');
          case 't' -> value.append('\t');
          case 'u' -> {
            if (i + 4 >= json.length()) throw invalid();
            int code = 0;
            for (int k = 0; k < 4; k++) {
              int digit = Character.digit(json.charAt(++i), 16);
              if (digit < 0) throw invalid();
              code = code * 16 + digit;
            }
            value.append((char) code);
          }
          default -> throw invalid();
        }
      }
      if (!closed) throw invalid();
      String decoded = value.toString();
      ContextSizer.utf8(decoded);
      guard.check(decoded);
      // 不能仅分别检查 api_key 和实际值：必须保留字段关系。这里只关联冒号后紧邻的字符串值，
      // 不让 null/数组/对象等值把字段名泄漏给后续无关 token，也不跨层猜测敏感载体。
      if (field != null) {
        DefaultSensitiveContentGuard.checkJsonField(field, decoded);
        field = null;
      }
      int next = skipWhitespace(json, i + 1);
      if (next < json.length() && json.charAt(next) == ':') {
        int start = skipWhitespace(json, next + 1);
        if (start < json.length()) {
          char first = json.charAt(start);
          if (first == '"') field = decoded;
          else if (first != '{' && first != '[') {
            // 数字/布尔/null 同样承载字段值；保留 null 占位语义，由正式解析器校验标量合法性。
            int end = start;
            while (end < json.length() && ",]} \t\r\n".indexOf(json.charAt(end)) < 0) end++;
            DefaultSensitiveContentGuard.checkJsonField(decoded, json.substring(start, end));
          }
        }
      }
    }
  }

  private static int skipWhitespace(String json, int start) {
    while (start < json.length() && " \t\r\n".indexOf(json.charAt(start)) >= 0) start++;
    return start;
  }

  private static AgentException invalid() {
    return new AgentException(AgentErrorCode.INVALID_RESPONSE);
  }
}

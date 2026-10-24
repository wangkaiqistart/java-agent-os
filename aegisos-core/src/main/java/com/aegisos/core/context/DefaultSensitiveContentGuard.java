package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import java.util.*;
import java.util.regex.Pattern;

/** 仅以启动时受控凭据快照和明确敏感载体拒绝内容，不扫描环境、替换正文或输出命中值。 JSON 转义必须由调用处先解析后检查；本端口不声称检测任意未知编码。 */
public final class DefaultSensitiveContentGuard implements SensitiveContentGuard {
  private static final Pattern CARRIER =
      Pattern.compile(
          "(?i)(?:[\\\"']?(?:api[_-]?key|password|token|secret)[\\\"']?\\s*[:=]\\s*)([\\\"']?)([^\\s,}\\\"']+)\\1");
  private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+([^\\s,;]+)");
  private final List<String> secrets;

  public DefaultSensitiveContentGuard(Collection<String> secrets) {
    this.secrets =
        secrets.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().toList();
  }

  @Override
  public void check(String content) {
    if (content == null || content.isEmpty()) return;
    for (String secret : secrets) if (content.contains(secret)) reject();
    if (Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----").matcher(content).find())
      reject();
    var bearer = BEARER.matcher(content);
    while (bearer.find()) if (!placeholder(bearer.group(1))) reject();
    var carrier = CARRIER.matcher(content);
    while (carrier.find()) if (!placeholder(carrier.group(2))) reject();
  }

  private static boolean placeholder(String value) {
    return value.equalsIgnoreCase("null")
        || value.matches("\\*+")
        || value.matches("(?i)<(?:YOUR_[A-Z0-9_]+|PLACEHOLDER|REDACTED|API_KEY|TOKEN|PASSWORD)>")
        || value.startsWith("${")
        || value.matches("(?i)(?:YOUR_[A-Z_]+|PLACEHOLDER|REDACTED|env:[A-Za-z_][A-Za-z0-9_]*)");
  }

  /**
   * JSON 已提供完整标量边界，不能再用文本正则只取首词。占位符必须完整匹配；例如 "null actual-value"、"env:REFERENCE actual-value"
   * 及引号开头的实际值都不是安全引用。 空字符串不承载实际值；已知凭据和标量正文仍由调用者按原规则检查。
   */
  static void checkJsonField(String field, String value) {
    if (!field.matches("(?i)(?:api[_-]?key|password|token|secret)") || value.isEmpty()) return;
    boolean reference = value.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    if (!reference && (value.startsWith("${") || !placeholder(value))) reject();
  }

  private static void reject() {
    throw new AgentException(AgentErrorCode.SENSITIVE_CONTENT);
  }

  @Override
  public String toString() {
    return "SensitiveContentGuard[redacted]";
  }
}

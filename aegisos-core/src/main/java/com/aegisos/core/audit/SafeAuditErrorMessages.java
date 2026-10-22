package com.aegisos.core.audit;

import com.aegisos.core.provider.ProviderErrorCode;

/** 仅从受控枚举生成审计说明；不接受 Throwable、厂商正文或自由文本。 */
public final class SafeAuditErrorMessages {
  public static final String HISTORICAL_UNKNOWN = "历史调用未成功，详细原因不可用";

  private SafeAuditErrorMessages() {}

  public static String forCode(ProviderErrorCode code) {
    if (code == null) return null;
    return switch (code) {
      case PROVIDER_TIMEOUT -> "模型调用超时";
      case AUTHENTICATION_FAILED -> "认证失败";
      case TEMPORARY_NETWORK_ERROR -> "临时网络错误";
      case RATE_LIMITED -> "请求受到限流";
      case AUDIT_UNAVAILABLE -> "审计服务不可用";
      case SESSION_REQUIRED -> "缺少会话追踪标识";
      case INVALID_SESSION_ID -> "会话追踪标识格式无效";
      case PROFILE_REQUIRED -> "缺少配置名称";
      case UNKNOWN_PROFILE -> "配置不存在或不可用";
      case PROVIDER_HTTP_ERROR, PROVIDER_PROTOCOL_ERROR -> "模型响应异常";
      default -> "请求配置无效或模型不可用";
    };
  }
}

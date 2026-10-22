package com.aegisos.core.provider;

/** Provider 的审计边界；旧入口明确不关联会话，新入口的 null 必须拒绝。 */
public interface ProviderService {
  /** 兼容调用：审计 session_id 为 null，不生成虚假会话。 */
  ModelResponse chat(ModelRequest request);

  /**
   * sessionId 只校验格式，不查会话；每次调用由服务生成独立 requestId。
   *
   * @param sessionId 1～64位ASCII追踪值，不trim；null/非法值均审计拒绝
   * @param request 原五字段请求，由统一边界校验，不能隐式补选Provider或模型
   * @return 安全关联requestId的文本/工具意图，不执行工具
   * @throws ProviderException 固定安全错误；审计无法提交时禁止外呼或后续重试
   */
  ModelResponse chat(String sessionId, ModelRequest request);
}

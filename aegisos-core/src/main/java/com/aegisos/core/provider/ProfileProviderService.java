package com.aegisos.core.provider;

/** 与 ProviderService 共用同一实现和审计/重试边界。 */
public interface ProfileProviderService {
  /**
   * 用启动时的Profile选择Provider、模型及温度，不允许请求覆盖路由，也不执行配置中的元数据。
   *
   * @param sessionId 调用方明确提供的合法追踪值，只校验格式，不证明会话存在或授权
   * @param profileName 精确区分大小写的Profile名称，缺失/未知均在外呼前审计拒绝
   * @param prompt 消息、工具schema及可空输出上限；工具名称必须在Profile允许集合内
   * @return 含独立requestId的文本/工具意图；不会执行工具或进入下一轮
   * @throws ProviderException 安全错误；与直接入口共用前置提交门槛、有限重试和终态故障处理
   */
  ModelResponse chatProfile(String sessionId, String profileName, ProfilePrompt prompt);

  /** 扩展实现接通前拒绝，绝不静默丢弃处理关联。 */
  default ModelResponse chatProfile(
      String sessionId, String profileName, ProfilePrompt prompt, ProviderInvocation invocation) {
    throw new UnsupportedOperationException("PROCESSING_CORRELATION_UNAVAILABLE");
  }
}

package com.aegisos.core.context;

/** 不透明安全端口；绝不返回命中值，不读取环境。 */
@FunctionalInterface
public interface SensitiveContentGuard {
  void check(String content);

  /** 对已定义为 JSON 的载体同时检查原文与解码后的字符串，不负责替代业务 JSON 校验。 */
  default void checkJson(String json) {
    check(json);
    JsonStringValues.check(json, this);
  }
}

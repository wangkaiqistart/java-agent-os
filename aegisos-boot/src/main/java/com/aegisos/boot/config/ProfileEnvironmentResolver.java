package com.aegisos.boot.config;

import com.aegisos.core.profile.ProfileDiagnostic.Code;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 只展开显式调用的 Provider 三字段，且仅允许全值引用。读取时机由启动加载器固定。 */
public final class ProfileEnvironmentResolver {
  private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
  private final Function<String, String> environment;

  public ProfileEnvironmentResolver(Function<String, String> environment) {
    this.environment = environment;
  }

  /**
   * 单次展开全值占位，不支持默认值/字符串插值/递归引用；非字符串原值留给类型校验。
   *
   * @param input Provider名称、模型或温度的原始字段；调用方限定这三处来源
   * @return 原值或启动时读取的环境值，不写入诊断/日志
   * @throws ProfileLoadException 引用形式错误或值缺失/为空；原异常与环境正文不传播
   */
  public Object resolve(Object input) {
    if (!(input instanceof String text) || !text.contains("${")) return input;
    var match = ENV.matcher(text);
    if (!match.matches()) throw new ProfileLoadException(Code.INVALID_FIELD);
    String value;
    try {
      value = environment.apply(match.group(1));
    } catch (RuntimeException e) {
      throw new ProfileLoadException(Code.ENV_MISSING);
    }
    if (value == null || value.isEmpty()) throw new ProfileLoadException(Code.ENV_MISSING);
    if (value.contains("${")) throw new ProfileLoadException(Code.INVALID_FIELD);
    return value;
  }
}

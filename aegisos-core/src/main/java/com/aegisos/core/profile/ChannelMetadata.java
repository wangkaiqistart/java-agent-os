package com.aegisos.core.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Channel 的不可变元数据，不创建连接，不允许秘密或端点字段。 */
public record ChannelMetadata(String name, Map<String, Object> config) {
  public ChannelMetadata {
    Profile.requireName(name);
    config = MetadataValue.map(config);
  }

  /** 返回只读映射；嵌套值已在构造时递归冻结，保留合法的 null 元数据。 */
  public Map<String, Object> config() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(config));
  }

  @Override
  public String toString() {
    return "ChannelMetadata[redacted]";
  }
}

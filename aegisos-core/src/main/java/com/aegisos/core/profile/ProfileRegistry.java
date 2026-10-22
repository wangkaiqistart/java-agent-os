package com.aegisos.core.profile;

import java.util.*;

/** 不可变、大小写敏感的只读索引；没有注册/删除入口。冲突应在加载阶段全部剔除。 */
public final class ProfileRegistry {
  private final Map<String, Profile> profiles;

  public ProfileRegistry(Collection<Profile> entries) {
    var result = new LinkedHashMap<String, Profile>();
    for (var profile : entries) {
      if (result.putIfAbsent(profile.name(), profile) != null) throw MetadataValue.invalid();
    }
    profiles = Collections.unmodifiableMap(result);
  }

  /** 按精确名称返回启动快照；不存在返回null，不回退默认Profile，也不触发磁盘读取。 */
  public Profile get(String name) {
    return profiles.get(name);
  }

  public Map<String, Profile> entries() {
    return profiles;
  }

  @Override
  public String toString() {
    return "ProfileRegistry[size=" + profiles.size() + "]";
  }
}

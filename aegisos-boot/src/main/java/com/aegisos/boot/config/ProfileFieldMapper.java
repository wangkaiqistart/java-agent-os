package com.aegisos.boot.config;

import com.aegisos.core.profile.*;
import com.aegisos.core.profile.ProfileDiagnostic.Code;
import com.aegisos.provider.config.ProviderRegistry;
import java.util.*;

/** 严格字段映射：扩展只存在于 channels.config/settings，不默默忽略未知配置。 */
public final class ProfileFieldMapper {
  private final ProviderRegistry providers;
  private final ProfileEnvironmentResolver environment;

  public ProfileFieldMapper(ProviderRegistry providers, ProfileEnvironmentResolver environment) {
    this.providers = providers;
    this.environment = environment;
  }

  /**
   * 将安全YAML树与目录技能绑定组装为深度不可变Profile，未知字段必须拒绝。
   *
   * @param raw 唯一profile.yml的受限解析结果，不从AGENT.md或第二目录合并
   * @param skills 已通过realpath检查的目录派生绑定；YAML中不得另声明skills
   * @return 完整快照；仅Provider三字段允许全值环境引用，缺凭据不阻止元数据注册
   * @throws ProfileLoadException 未知Provider、越界模型或非法值，只保留固定错误代码
   */
  public Profile map(Map<String, Object> raw, List<SkillBinding> skills) {
    try {
      keys(
          raw,
          "name",
          "description",
          "identity",
          "provider",
          "tools",
          "mcp_servers",
          "channels",
          "notify_channels",
          "schedules",
          "bootstrap",
          "settings");
      var selection = object(raw.get("provider"), false);
      keys(selection, "name", "model", "temperature");
      var provider =
          new ProviderSelection(
              string(environment.resolve(selection.get("name")), false),
              string(environment.resolve(selection.get("model")), false),
              temperature(environment.resolve(selection.get("temperature"))));
      var registration = providers.get(provider.name());
      if (registration == null) throw new ProfileLoadException(Code.UNKNOWN_PROVIDER);
      if (!registration.definition().models().contains(provider.model()))
        throw new ProfileLoadException(Code.MODEL_UNAVAILABLE);
      Identity identity = null;
      if (raw.get("identity") != null) {
        var i = object(raw.get("identity"), false);
        keys(i, "agent_name", "prompt");
        identity = new Identity(string(i.get("agent_name"), true), string(i.get("prompt"), true));
      }
      var channels = new ArrayList<ChannelMetadata>();
      for (Object item : list(raw.get("channels"))) {
        var c = object(item, false);
        keys(c, "name", "config");
        channels.add(
            new ChannelMetadata(string(c.get("name"), false), object(c.get("config"), true)));
      }
      var schedules = new ArrayList<ScheduleMetadata>();
      for (Object item : list(raw.get("schedules"))) {
        var s = object(item, false);
        keys(s, "key", "cron", "zone", "message", "display_name");
        schedules.add(
            new ScheduleMetadata(
                string(s.get("key"), false),
                string(s.get("cron"), false),
                string(s.get("zone"), false),
                string(s.get("message"), false),
                string(s.get("display_name"), true)));
      }
      var settings = new LinkedHashMap<>(object(raw.get("settings"), true));
      var it = integer(settings.remove("max_iterations"));
      var history = integer(settings.remove("max_history_turns"));
      return new Profile(
          string(raw.get("name"), false),
          string(raw.get("description"), true),
          identity,
          provider,
          strings(raw.get("tools")),
          skills,
          strings(raw.get("mcp_servers")),
          channels,
          strings(raw.get("notify_channels")),
          schedules,
          strings(raw.get("bootstrap")),
          new Settings(it, history, settings));
    } catch (ProfileLoadException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ProfileLoadException(Code.INVALID_FIELD);
    }
  }

  static String string(Object v, boolean nullable) {
    if (v == null && nullable) return null;
    if (!(v instanceof String s)) throw new ProfileLoadException(Code.INVALID_FIELD);
    return s;
  }

  static Map<String, Object> object(Object v, boolean optional) {
    if (v == null && optional) return Map.of();
    if (!(v instanceof Map<?, ?> m)) throw new ProfileLoadException(Code.INVALID_FIELD);
    var result = new LinkedHashMap<String, Object>();
    for (var e : m.entrySet()) {
      if (!(e.getKey() instanceof String key)) throw new ProfileLoadException(Code.INVALID_FIELD);
      result.put(key, e.getValue());
    }
    return result;
  }

  static List<?> list(Object v) {
    if (v == null) return List.of();
    if (!(v instanceof List<?> l) || l.size() > 256)
      throw new ProfileLoadException(Code.INVALID_FIELD);
    return l;
  }

  static List<String> strings(Object v) {
    return list(v).stream().map(x -> string(x, false)).toList();
  }

  static void keys(Map<String, Object> map, String... allowed) {
    if (!Set.of(allowed).containsAll(map.keySet()))
      throw new ProfileLoadException(Code.INVALID_FIELD);
  }

  static Integer integer(Object v) {
    if (v == null) return null;
    if (!(v instanceof Byte
        || v instanceof Short
        || v instanceof Integer
        || v instanceof Long
        || v instanceof java.math.BigInteger)) throw new ProfileLoadException(Code.INVALID_FIELD);
    try {
      return new java.math.BigInteger(v.toString()).intValueExact();
    } catch (RuntimeException e) {
      throw new ProfileLoadException(Code.INVALID_FIELD);
    }
  }

  static Double temperature(Object v) {
    if (v == null) return null;
    if (!(v instanceof Number || v instanceof String))
      throw new ProfileLoadException(Code.INVALID_FIELD);
    try {
      return Double.valueOf(v.toString());
    } catch (RuntimeException e) {
      throw new ProfileLoadException(Code.INVALID_FIELD);
    }
  }
}

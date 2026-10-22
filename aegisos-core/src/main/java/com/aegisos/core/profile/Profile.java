package com.aegisos.core.profile;

import java.nio.file.Path;
import java.util.*;

/**
 * 启动时生成的 Agent 运行配置快照。所有集合及扩展树被冻结，运行时没有重载接口。
 *
 * @param name 精确且区分大小写的安全名称，可与目录名不同
 * @param description 可空说明，最多4096字符，不展开环境引用
 * @param identity 可空身份元数据，本轮不注入Prompt
 * @param provider 必填的显式Provider/模型及可空温度
 * @param tools 可传递schema的名称允许集合，不生成或执行工具
 * @param skills 仅由启动时验证的相对软连接派生的绑定
 * @param mcpServers MCP名称引用，本轮不连接
 * @param channels 受限不可变渠道元数据，本轮不建连接
 * @param notifyChannels 通知名称引用，不保存URL或凭据
 * @param schedules 仅结构校验的计划元数据，不注册定时任务
 * @param bootstrap 仅安全相对文件名，本轮不读文件
 * @param settings 可空正整数上限及受限扩展树，不赋予ReAct行为
 * @throws IllegalArgumentException 任一不变量不满足，错误仅为固定INVALID_FIELD
 */
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderSelection provider,
    List<String> tools,
    List<SkillBinding> skills,
    List<String> mcpServers,
    List<ChannelMetadata> channels,
    List<String> notifyChannels,
    List<ScheduleMetadata> schedules,
    List<String> bootstrap,
    Settings settings) {
  public Profile {
    requireName(name);
    if (description != null && description.length() > 4096 || provider == null)
      throw MetadataValue.invalid();
    tools = names(tools, true);
    mcpServers = names(mcpServers, false);
    notifyChannels = names(notifyChannels, false);
    skills = copy(skills);
    channels = copy(channels);
    schedules = copy(schedules);
    bootstrap = copy(bootstrap);
    unique(skills.stream().map(SkillBinding::name).toList());
    unique(channels.stream().map(ChannelMetadata::name).toList());
    unique(schedules.stream().map(ScheduleMetadata::key).toList());
    bootstrap.forEach(Profile::relative);
    if (settings == null) settings = new Settings(null, null, Map.of());
  }

  // 每个访问器再次返回不可变副本，使快照边界对调用者和静态分析均明确。
  public List<String> tools() {
    return List.copyOf(tools);
  }

  public List<SkillBinding> skills() {
    return List.copyOf(skills);
  }

  public List<String> mcpServers() {
    return List.copyOf(mcpServers);
  }

  public List<ChannelMetadata> channels() {
    return List.copyOf(channels);
  }

  public List<String> notifyChannels() {
    return List.copyOf(notifyChannels);
  }

  public List<ScheduleMetadata> schedules() {
    return List.copyOf(schedules);
  }

  public List<String> bootstrap() {
    return List.copyOf(bootstrap);
  }

  private static <T> List<T> copy(List<T> list) {
    if (list != null && list.size() > 256) throw MetadataValue.invalid();
    try {
      return list == null ? List.of() : List.copyOf(list);
    } catch (RuntimeException e) {
      throw MetadataValue.invalid();
    }
  }

  private static List<String> names(List<String> list, boolean tool) {
    var copy = copy(list);
    for (String s : copy) {
      if (tool) {
        if (s == null || !s.matches("[A-Za-z0-9_-]{1,64}")) throw MetadataValue.invalid();
      } else requireName(s);
    }
    unique(copy);
    return copy;
  }

  private static void unique(List<String> list) {
    if (new HashSet<>(list).size() != list.size()) throw MetadataValue.invalid();
  }

  public static void requireName(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
      throw MetadataValue.invalid();
  }

  public static void relative(String value) {
    if (value == null || value.isBlank() || value.contains("\\") || value.contains(":"))
      throw MetadataValue.invalid();
    try {
      Path p = Path.of(value);
      if (p.isAbsolute()) throw MetadataValue.invalid();
      for (Path part : p) if (part.toString().equals("..")) throw MetadataValue.invalid();
    } catch (RuntimeException e) {
      throw MetadataValue.invalid();
    }
  }

  @Override
  public String toString() {
    return "Profile[redacted]";
  }
}

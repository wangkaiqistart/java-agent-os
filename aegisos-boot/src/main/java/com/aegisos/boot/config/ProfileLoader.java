package com.aegisos.boot.config;

import com.aegisos.core.profile.*;
import com.aegisos.core.profile.ProfileDiagnostic.Code;
import com.aegisos.provider.config.ProviderRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** 启动一次性加载。先收集安全名称，再剔除全部重名候选；坏文件不阻断其他合法项。 */
public final class ProfileLoader {
  private final ProfilePathPolicy paths;
  private final ProfileFieldMapper mapper;

  public ProfileLoader(Path root, ProviderRegistry providers, Function<String, String> env) {
    paths = new ProfilePathPolicy(root);
    mapper = new ProfileFieldMapper(providers, new ProfileEnvironmentResolver(env));
  }

  private record Candidate(String name, Profile profile, String source) {}

  /**
   * 两阶段生成完整快照，不从缺失文件回退读取AGENT.md。
   *
   * @return 有效配置与固定安全诊断；未知字段/错误环境值不会进入报告
   * @throws ProfileLoadException 工作区/Agent根本身越界时拒绝建立索引
   */
  public ProfileLoadReport load() {
    var diagnostics = new ArrayList<ProfileDiagnostic>();
    var candidates = new ArrayList<Candidate>();
    Path agents = paths.agents();
    if (!Files.exists(agents, LinkOption.NOFOLLOW_LINKS))
      return new ProfileLoadReport(
          List.of(), List.of(new ProfileDiagnostic(Code.AGENT_ROOT_MISSING, ".aegisos/agents")));
    paths.directory(agents);
    try (var stream = Files.list(agents)) {
      for (Path agent : stream.sorted().toList()) {
        if (!Files.isDirectory(agent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(agent))
          continue;
        String source = paths.source(agent.resolve("profile.yml"));
        String name = null;
        Profile profile = null;
        try {
          paths.directory(agent);
          Path file = agent.resolve("profile.yml");
          if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            throw new ProfileLoadException(Code.PROFILE_FILE_MISSING);
          var raw = new SafeProfileYamlReader().read(paths.profile(agent));
          if (raw.get("name") instanceof String n) {
            try {
              Profile.requireName(n);
              name = n;
            } catch (IllegalArgumentException ignored) {
            }
          }
          profile = mapper.map(raw, paths.skills(agent));
        } catch (ProfileLoadException e) {
          diagnostics.add(new ProfileDiagnostic(e.code, source));
        }
        candidates.add(new Candidate(name, profile, source));
      }
    } catch (IOException e) {
      diagnostics.add(new ProfileDiagnostic(Code.READ_FAILED, ".aegisos/agents"));
    }
    var counts = new HashMap<String, Integer>();
    for (var c : candidates) if (c.name() != null) counts.merge(c.name(), 1, Integer::sum);
    var profiles = new ArrayList<Profile>();
    for (var c : candidates) {
      if (c.name() != null && counts.get(c.name()) > 1)
        diagnostics.add(new ProfileDiagnostic(Code.DUPLICATE_PROFILE, c.source()));
      else if (c.profile() != null) profiles.add(c.profile());
    }
    if (candidates.isEmpty())
      diagnostics.add(new ProfileDiagnostic(Code.AGENT_ROOT_EMPTY, ".aegisos/agents"));
    return new ProfileLoadReport(profiles, diagnostics);
  }
}

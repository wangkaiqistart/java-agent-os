package com.aegisos.boot.config;

import com.aegisos.core.profile.*;
import com.aegisos.core.profile.ProfileDiagnostic.Code;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** realpath 边界用于可信管理员工作区，不能宣称防敌对并发替换的 OS 沙箱。 */
public final class ProfilePathPolicy {
  private final Path root;

  /** 固定可信工作区的真实路径；不存在/不可读时只抛安全READ_FAILED，不携带系统路径异常。 */
  public ProfilePathPolicy(Path workspace) {
    try {
      root = workspace.toRealPath();
      if (!Files.isDirectory(root)) throw new IOException();
    } catch (IOException | RuntimeException e) {
      throw new ProfileLoadException(Code.READ_FAILED);
    }
  }

  public Path agents() {
    Path aegis = root.resolve(".aegisos");
    if (Files.exists(aegis, LinkOption.NOFOLLOW_LINKS)) directory(aegis);
    return root.resolve(".aegisos/agents");
  }

  public Path directory(Path p) {
    return checked(p, true, Code.READ_FAILED);
  }

  /** 返回唯一profile.yml入口；真实文件必须仍位于工作区边界内，不读文件内容。 */
  public Path profile(Path agent) {
    Path p = agent.resolve("profile.yml");
    checked(p, false, Code.READ_FAILED);
    return p;
  }

  private Path checked(Path p, boolean directory, Code code) {
    try {
      Path real = p.toRealPath();
      if (!real.startsWith(root)
          || (directory ? !Files.isDirectory(real) : !Files.isRegularFile(real)))
        throw new IOException();
      return real;
    } catch (IOException | RuntimeException e) {
      throw new ProfileLoadException(code);
    }
  }

  /**
   * 仅从Agent的skills目录读取绑定：每项必须为相对软连接，真实目录限定在公共skills根内。
   *
   * @param agent 已校验的Agent目录，不接受独立profiles来源
   * @return 不可变绑定列表；目录不存在时为空，不读取SKILL.md正文
   * @throws ProfileLoadException 任意非法名称、绝对/越界/悬空软连接或I/O失败，统一安全代码
   */
  public List<SkillBinding> skills(Path agent) {
    Path container = agent.resolve("skills");
    if (!Files.exists(container, LinkOption.NOFOLLOW_LINKS)) return List.of();
    Path actual = checked(container, true, Code.INVALID_SKILL_BINDING);
    Path common = checked(root.resolve(".aegisos/skills"), true, Code.INVALID_SKILL_BINDING);
    var result = new ArrayList<SkillBinding>();
    try (var stream = Files.list(actual)) {
      for (Path binding : stream.sorted().toList()) {
        Path fileName = binding.getFileName();
        if (fileName == null) throw new IOException();
        String name = fileName.toString();
        Profile.requireName(name);
        if (!Files.isSymbolicLink(binding) || Files.readSymbolicLink(binding).isAbsolute())
          throw new IOException();
        Path target = binding.toRealPath();
        if (!target.startsWith(common) || !Files.isDirectory(target)) throw new IOException();
        result.add(
            new SkillBinding(
                name,
                root.relativize(binding).toString().replace(java.io.File.separatorChar, '/'),
                root.relativize(target).toString().replace(java.io.File.separatorChar, '/')));
      }
      return List.copyOf(result);
    } catch (IOException | RuntimeException e) {
      throw new ProfileLoadException(Code.INVALID_SKILL_BINDING);
    }
  }

  public String source(Path p) {
    try {
      String relative = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
      return new ProfileDiagnostic(Code.READ_FAILED, relative).source();
    } catch (RuntimeException e) {
      return "<invalid-source>";
    }
  }
}

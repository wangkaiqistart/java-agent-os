package com.aegisos.boot.cli;

import com.aegisos.core.context.DefaultSensitiveContentGuard;
import com.aegisos.core.platform.OwnerOnlyFiles;
import com.aegisos.core.profile.Profile;
import com.aegisos.core.provider.SafeProviderMetadata;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 仅管理定义文件。创建不覆盖；删除仅原子移出，不遍历/删除技能目标或触碰任何历史数据。 */
public final class ProfileDefinitionService {
  private final Path root;
  private final WorkspaceMetadataService metadata;

  public ProfileDefinitionService(Path workspace) {
    metadata = new WorkspaceMetadataService(workspace);
    root = Path.of(metadata.properties().get("aegisos.workspace-root").toString());
  }

  public void create(String name, String provider, String model) {
    var created = new ArrayList<Path>();
    try {
      Profile.requireName(name);
      if (SafeProviderMetadata.provider(provider) == null
          || SafeProviderMetadata.model(model) == null) throw refused();
      var guard = new DefaultSensitiveContentGuard(List.of());
      guard.check(name);
      guard.check(provider);
      guard.check(model);
      var definition =
          metadata.providers().stream()
              .filter(d -> d.name().equals(provider))
              .findFirst()
              .orElseThrow(ProfileDefinitionService::refused);
      if (!definition.models().contains(model)
          || definition.baseUrl() == null
          || definition.credentialRef() == null
          || !"openai-compatible".equals(definition.adapter())
          || definition.maxRetries() < 0
          || !definition.connectTimeout().isPositive()
          || !definition.readTimeout().isPositive()) throw refused();
      // 名称与目录必须一起唯一，不能让不同物理目录加载出同名Profile。
      if (metadata.report().profiles().stream().anyMatch(p -> p.name().equals(name)))
        throw refused();
      Path agent = root.resolve(".aegisos/agents").resolve(name);
      parents(parent(agent));
      directory(agent);
      created.add(agent);
      Path skills = agent.resolve("skills");
      directory(skills);
      created.add(skills);
      write(
          agent.resolve("profile.yml"),
          "name: '"
              + name
              + "'\nprovider:\n  name: '"
              + provider
              + "'\n  model: '"
              + model
              + "'\ntools: []\nbootstrap: []\nsettings:\n  max_iterations: 10\n  max_history_turns: 20\n",
          created);
      write(agent.resolve("AGENT.md"), "请根据用户请求给出简短、安全的中文答复。\n", created);
    } catch (IOException | RuntimeException e) {
      Collections.reverse(created);
      for (var path : created)
        try {
          if (!Files.isSymbolicLink(path)) Files.deleteIfExists(path);
        } catch (IOException ignored) {
          /* 不清理非本次内容。 */
        }
      throw refused();
    }
  }

  public void delete(String name) {
    try {
      Profile.requireName(name);
      // 来源必须来自本次严格加载结果，不能用CLI名称直接拼路径删除。
      Path source = metadata.report().sources().require(name).agentDirectory();
      parents(source);
      if (!parent(source).equals(root.resolve(".aegisos/agents"))) throw refused();
      Path recycle = root.resolve(".aegisos/deleted-agents");
      parents(parent(recycle));
      if (!Files.exists(recycle, LinkOption.NOFOLLOW_LINKS)) directory(recycle);
      parents(recycle);
      Path target = recycle.resolve(UUID.randomUUID().toString());
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw refused();
      // 不支持原子移动即拒绝；不能回退成逐文件删除，避免失败留下半个定义。
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      throw refused();
    }
  }

  private void write(Path path, String content, List<Path> created) throws IOException {
    parents(parent(path));
    try (var out = OwnerOnlyFiles.newOutputStream(path)) {
      created.add(path);
      out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private static void directory(Path path) throws IOException {
    OwnerOnlyFiles.createDirectory(path);
  }

  private void parents(Path path) throws IOException {
    if (!path.normalize().startsWith(root)) throw refused();
    for (var p = path; p != null && p.startsWith(root); p = p.getParent())
      if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
        throw refused();
  }

  /** Path契约允许根路径没有父目录；在任何文件操作前固定拒绝，不依赖隐式NPE。 */
  private static Path parent(Path path) {
    Path parent = path.getParent();
    if (parent == null) throw refused();
    return parent;
  }

  private static IllegalArgumentException refused() {
    return new IllegalArgumentException("PROFILE_DEFINITION_REJECTED");
  }
}

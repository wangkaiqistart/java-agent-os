package com.aegisos.cli;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** 离线初始化。预检整棵目标树，仅 CREATE_NEW；失败只逆序删除本次创建且仍为普通项的路径。 */
public final class InitCommand {
  public void run(Path workspace) {
    var created = new ArrayList<Path>();
    try {
      if (!workspace.isAbsolute() || !Files.isDirectory(workspace)) throw refused();
      Path root = workspace.toRealPath();
      var files = new LinkedHashMap<String, byte[]>();
      files.put(".aegisos/config.yml", template("config.yml"));
      files.put(".aegisos/agents/weather/profile.yml", template("profile.yml"));
      files.put(".aegisos/agents/weather/AGENT.md", template("AGENT.md"));
      for (String name : List.of("AGENTS.md", "SOUL.md", "USER.md"))
        files.put(
            ".aegisos/" + name,
            "<!-- 可选指令：按需填写，不要放凭据。 -->\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      // 现有目录也拒绝，避免把一次初始化混入未知个人工作区或软连接树。
      if (Files.exists(root.resolve(".aegisos"), LinkOption.NOFOLLOW_LINKS)) throw refused();
      for (String name :
          List.of(
              ".aegisos",
              ".aegisos/agents",
              ".aegisos/agents/weather",
              ".aegisos/skills",
              ".aegisos/agents/weather/skills")) {
        Path path = root.resolve(name);
        verifyParents(root, path.getParent());
        try {
          Files.createDirectory(
              path,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
          Files.createDirectory(path);
        }
        created.add(path);
      }
      for (var entry : files.entrySet()) {
        Path path = root.resolve(entry.getKey());
        verifyParents(root, path.getParent());
        try (var out =
            Files.newOutputStream(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
          created.add(path);
          out.write(entry.getValue());
        }
      }
    } catch (IOException | RuntimeException e) {
      Collections.reverse(created);
      for (Path path : created)
        try {
          if (!Files.isSymbolicLink(path)) Files.deleteIfExists(path);
        } catch (IOException ignored) {
          /* 不删除非空目录或任何后来产生的文件。 */
        }
      throw refused();
    }
  }

  private static void verifyParents(Path root, Path parent) throws IOException {
    for (Path current = parent;
        current != null && current.startsWith(root);
        current = current.getParent())
      if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
        throw refused();
  }

  private byte[] template(String name) throws IOException {
    try (var in = InitCommand.class.getResourceAsStream("/templates/" + name)) {
      if (in == null) throw refused();
      return in.readAllBytes();
    }
  }

  private static IllegalStateException refused() {
    return new IllegalStateException("INIT_REFUSED");
  }
}

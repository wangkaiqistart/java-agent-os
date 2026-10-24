package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.profile.Profile;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 每轮读取正文；先验证启动来源，再使用目录描述符及 NOFOLLOW_LINKS 打开已解析的普通文件。 */
public final class ContextLoader implements ContextSourceLoader {
  public record LoadedContext(String instructions, List<String> warnings) {
    public LoadedContext {
      warnings = List.copyOf(warnings);
    }

    @Override
    public String toString() {
      return "LoadedContext[redacted]";
    }
  }

  private final ContextBudget budget;
  private final SensitiveContentGuard guard;

  public ContextLoader(ContextBudget budget, SensitiveContentGuard guard) {
    this.budget = budget;
    this.guard = guard;
  }

  public LoadedContext load(Profile profile, AgentSource source) {
    try {
      Path live = source.workspaceRoot().resolve(source.relativeDirectory()).toRealPath();
      if (!live.equals(source.agentDirectory()) || !live.startsWith(source.workspaceRoot()))
        throw unavailable();
      var parts = new ArrayList<String>();
      var warnings = new ArrayList<String>();
      int[] count = {0};
      add(parts, live, live.resolve("AGENT.md"), count);
      Path approved = source.workspaceRoot().resolve(".aegisos").toRealPath();
      if (!approved.startsWith(source.workspaceRoot())) throw unavailable();
      boolean defaults = profile.bootstrap().isEmpty();
      var names = defaults ? List.of("AGENTS.md", "SOUL.md", "USER.md") : profile.bootstrap();
      for (String name : names) {
        Path file = approved.resolve(name).normalize();
        if (!file.startsWith(approved)) throw unavailable();
        if (defaults)
          try {
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          } catch (NoSuchFileException missing) {
            warnings.add("OPTIONAL_FILE_MISSING");
            continue;
          }
        add(parts, approved, file, count);
      }
      for (var skill :
          profile.skills().stream()
              .sorted(Comparator.comparing(com.aegisos.core.profile.SkillBinding::name))
              .toList()) {
        Path binding = source.workspaceRoot().resolve(skill.bindingPath());
        Path expected = source.workspaceRoot().resolve(skill.targetPath()).normalize();
        Path common = approved.resolve("skills").toRealPath();
        if (!common.startsWith(approved)
            || !Files.isSymbolicLink(binding)
            || Files.readSymbolicLink(binding).isAbsolute()
            || !binding.toRealPath().equals(expected)
            || !expected.toRealPath().equals(expected)
            || !expected.startsWith(common)
            || !Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        add(parts, expected, expected.resolve("SKILL.md"), count);
      }
      String body = String.join("\n\n", parts);
      if (ContextSizer.utf8(body) > budget.maxContextBytes())
        throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
      return new LoadedContext(body, warnings);
    } catch (IOException e) {
      throw unavailable();
    }
  }

  private void add(List<String> parts, Path approved, Path file, int[] count) throws IOException {
    if (++count[0] > budget.maxContextFiles())
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    parts.add(read(approved, file));
  }

  private String read(Path approvedRoot, Path file) throws IOException {
    Path realRoot = approvedRoot.toRealPath(), realFile = file.toRealPath();
    if (!realRoot.equals(approvedRoot)
        || !realFile.startsWith(realRoot)
        || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
    var streams = new ArrayList<DirectoryStream<Path>>();
    try {
      DirectoryStream<Path> opened = Files.newDirectoryStream(realRoot);
      streams.add(opened);
      if (!(opened instanceof SecureDirectoryStream<Path> current)) throw unavailable();
      Path relative = realRoot.relativize(realFile);
      for (int i = 0; i < relative.getNameCount() - 1; i++) {
        var child = current.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
        streams.add(child);
        current = child;
      }
      Path name = relative.getFileName();
      var view =
          current.getFileAttributeView(
              name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes before = view.readAttributes();
      if (!before.isRegularFile()) throw unavailable();
      if (before.size() > budget.maxContextFileBytes())
        throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
      byte[] bytes;
      try (var channel =
              current.newByteChannel(
                  name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          var input = Channels.newInputStream(channel)) {
        bytes = input.readNBytes(budget.maxContextFileBytes() + 1);
      }
      if (bytes.length > budget.maxContextFileBytes())
        throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
      BasicFileAttributes after = view.readAttributes();
      if (!after.isRegularFile()
          || !Objects.equals(before.fileKey(), after.fileKey())
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !file.toRealPath().equals(realFile)) throw unavailable();
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      guard.check(text);
      return text;
    } finally {
      for (int i = streams.size() - 1; i >= 0; i--) streams.get(i).close();
    }
  }

  private static AgentException unavailable() {
    return new AgentException(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
  }
}

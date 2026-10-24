package com.aegisos.core.context;

import com.aegisos.core.agent.*;
import com.aegisos.core.profile.Profile;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
    byte[] bytes;
    try {
      bytes =
          com.aegisos.core.platform.SecureFileAccess.read(
              realRoot, realFile, budget.maxContextFileBytes());
    } catch (com.aegisos.core.platform.SecureFileAccess.TooLargeException e) {
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    }
    if (!file.toRealPath().equals(realFile)) throw unavailable();
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    guard.check(text);
    return text;
  }

  private static AgentException unavailable() {
    return new AgentException(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
  }
}

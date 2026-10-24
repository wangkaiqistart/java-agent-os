package com.aegisos.core.platform;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 平台文件端口。只返回有界字节；不泄露句柄，不创建正文文件，也不降级为普通路径读取。 */
public final class SecureFileAccess {
  private SecureFileAccess() {}

  public static final class TooLargeException extends IOException {
    public TooLargeException() {
      super("FILE_LIMIT");
    }
  }

  public static byte[] read(Path root, Path file, int limit) throws IOException {
    if (limit < 0
        || limit == Integer.MAX_VALUE
        || !root.isAbsolute()
        || !root.equals(root.toRealPath())
        || !file.startsWith(root)
        || file.equals(root)
        || !file.equals(file.toRealPath())) throw unavailable();
    byte[] result;
    try {
      if (com.sun.jna.Platform.isMac()) result = DarwinFiles.read(root, file, limit);
      else if (com.sun.jna.Platform.isWindows()) result = WindowsFiles.read(root, file, limit);
      else result = readDirectoryStream(root, file, limit);
    } catch (LinkageError e) {
      throw unavailable(); // 缺少原生库/符号也必须固定拒绝，不能弱化安全路径。
    }
    if (!file.equals(file.toRealPath())) throw unavailable();
    return result;
  }

  private static byte[] readDirectoryStream(Path root, Path file, int limit) throws IOException {
    var streams = new ArrayList<DirectoryStream<Path>>();
    try {
      var opened = Files.newDirectoryStream(root);
      streams.add(opened);
      if (!(opened instanceof SecureDirectoryStream<Path> current)) throw unavailable();
      Path relative = root.relativize(file);
      for (int i = 0; i < relative.getNameCount() - 1; i++) {
        var child = current.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
        streams.add(child);
        current = child;
      }
      Path name = relative.getFileName();
      var view =
          current.getFileAttributeView(
              name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      var before = view.readAttributes();
      if (!before.isRegularFile() || before.fileKey() == null) throw unavailable();
      if (before.size() > limit) throw new TooLargeException();
      byte[] bytes;
      try (var channel =
              current.newByteChannel(
                  name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
          var input = Channels.newInputStream(channel)) {
        bytes = input.readNBytes(limit + 1);
      }
      if (bytes.length > limit) throw new TooLargeException();
      var after = view.readAttributes();
      if (!after.isRegularFile()
          || !before.fileKey().equals(after.fileKey())
          || before.size() != after.size()
          || bytes.length != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw unavailable();
      return bytes;
    } finally {
      IOException failure = null;
      for (int i = streams.size() - 1; i >= 0; i--) {
        try {
          streams.get(i).close();
        } catch (IOException e) {
          failure = unavailable();
        }
      }
      if (failure != null) throw failure;
    }
  }

  /** 数据库及独立锁必须是可验证单链接的普通文件；Windows不能跳过链接数检查。 */
  public static void requireSingleLink(Path file) throws IOException {
    try {
      if (com.sun.jna.Platform.isWindows()) WindowsFiles.requireSingleLink(file);
      else {
        var attributes =
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object n = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || !(n instanceof Number count) || count.longValue() != 1)
          throw unavailable();
      }
    } catch (LinkageError | UnsupportedOperationException e) {
      throw unavailable();
    }
  }

  static IOException unavailable() {
    return new IOException("PLATFORM_FILE_UNAVAILABLE");
  }
}

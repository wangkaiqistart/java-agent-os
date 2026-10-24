package com.aegisos.core.platform;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 创建时即使用私有权限。既有对象不改权限；不支持POSIX或ACL的文件系统固定拒绝。 */
public final class OwnerOnlyFiles {
  private OwnerOnlyFiles() {}

  private static FileAttribute<?> attributes(Path path, boolean directory) throws IOException {
    var fs = path.getFileSystem();
    if (fs.supportedFileAttributeViews().contains("posix"))
      return PosixFilePermissions.asFileAttribute(
          PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    throw SecureFileAccess.unavailable();
  }

  public static void createDirectory(Path directory) throws IOException {
    if (com.sun.jna.Platform.isWindows()) WindowsPrivateFiles.createDirectory(directory);
    else Files.createDirectory(directory, attributes(directory, true));
  }

  /** 每一级新建目录都应用私有权限，既有父目录仅验证类型，不跟随软链接。 */
  public static void createDirectories(Path directory) throws IOException {
    Path path = directory.toAbsolutePath().normalize();
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
        throw SecureFileAccess.unavailable();
      return;
    }
    Path parent = path.getParent();
    if (parent == null) throw SecureFileAccess.unavailable();
    createDirectories(parent);
    try {
      createDirectory(path);
    } catch (FileAlreadyExistsException e) {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
        throw SecureFileAccess.unavailable();
    }
  }

  public static void createFile(Path file) throws IOException {
    if (com.sun.jna.Platform.isWindows()) {
      try (var ignored = WindowsPrivateFiles.newOutputStream(file)) {
        /* 创建后关闭，不写正文。 */
      }
    } else Files.createFile(file, attributes(file, false));
  }

  public static OutputStream newOutputStream(Path file) throws IOException {
    if (com.sun.jna.Platform.isWindows()) return WindowsPrivateFiles.newOutputStream(file);
    return Channels.newOutputStream(
        Files.newByteChannel(
            file,
            Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            attributes(file, false)));
  }

  /** SQLite本地路径：盘符仅在Windows允许；禁止URI、查询参数、ADS和共享路径。 */
  public static Path databasePath(String file) throws IOException {
    if (file == null
        || file.isBlank()
        || file.contains("?")
        || file.startsWith("\\\\")
        || file.startsWith("//")) throw SecureFileAccess.unavailable();
    String tail = file;
    if (com.sun.jna.Platform.isWindows() && file.matches("(?i)^[a-z]:[\\\\/].*"))
      tail = file.substring(2);
    if (tail.contains(":")) throw SecureFileAccess.unavailable();
    Path path = Path.of(file).toAbsolutePath().normalize();
    if (com.sun.jna.Platform.isWindows()) WindowsFiles.localVolume(path);
    return path;
  }
}

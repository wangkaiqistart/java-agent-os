package com.aegisos.core.platform;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Darwin 64位 ABI：通过 openat 固定逐级目录身份，O_NOFOLLOW 禁止末端替换为软链接。 */
final class DarwinFiles {
  private static final int DIRECTORY = 0x00100000,
      NOFOLLOW = 0x100,
      CLOEXEC = 0x01000000,
      NONBLOCK = 4;
  private static final int TYPE_MASK = 0170000, REGULAR = 0100000, DIR = 0040000;

  private DarwinFiles() {}

  interface LibC extends Library {
    int open(String path, int flags);

    int openat(int fd, String name, int flags);

    int fstat(int fd, Pointer stat);

    NativeLong read(int fd, Pointer bytes, NativeLong count);

    int close(int fd);
  }

  private static final class Api {
    // Darwin x86_64 的 inode64 符号与 arm64 无后缀符号使用相同的 144字节布局。
    static final LibC LIB =
        Native.load(
            "System",
            LibC.class,
            Map.of(
                Library.OPTION_FUNCTION_MAPPER,
                (FunctionMapper)
                    (library, method) ->
                        method.getName().equals("fstat") && !Platform.isARM()
                            ? "fstat$INODE64"
                            : method.getName(),
                Library.OPTION_STRING_ENCODING,
                "UTF-8"));
  }

  record Stat(
      long dev, long inode, int mode, long size, long mtime, long mnano, long ctime, long cnano) {}

  private static Stat stat(int fd) throws IOException {
    // struct stat: dev@0, mode@4, ino@8, mtime@48, ctime@64, size@96 (Darwin LP64)。
    try (var memory = new Memory(256)) {
      memory.clear();
      if (Api.LIB.fstat(fd, memory) != 0) throw SecureFileAccess.unavailable();
      return new Stat(
          Integer.toUnsignedLong(memory.getInt(0)),
          memory.getLong(8),
          Short.toUnsignedInt(memory.getShort(4)),
          memory.getLong(96),
          memory.getLong(48),
          memory.getLong(56),
          memory.getLong(64),
          memory.getLong(72));
    }
  }

  /** 用JDK的独立元数据交叉检查ABI及路径身份，避免未知布局被误识别为有效文件。 */
  private static void samePath(Path path, Stat stat, int type) throws IOException {
    var a = Files.readAttributes(path, "unix:dev,ino,mode,size", LinkOption.NOFOLLOW_LINKS);
    if ((stat.mode & TYPE_MASK) != type
        || stat.size < 0
        || !(a.get("dev") instanceof Number dev)
        || dev.longValue() != stat.dev
        || !(a.get("ino") instanceof Number ino)
        || ino.longValue() != stat.inode
        || !(a.get("mode") instanceof Number mode)
        || mode.intValue() != stat.mode
        || !(a.get("size") instanceof Number size)
        || size.longValue() != stat.size) throw SecureFileAccess.unavailable();
  }

  static byte[] read(Path root, Path file, int limit) throws IOException {
    if (Native.POINTER_SIZE != 8
        || Native.LONG_SIZE != 8
        || !(Platform.isARM()
            || System.getProperty("os.arch").equals("x86_64")
            || System.getProperty("os.arch").equals("amd64"))) throw SecureFileAccess.unavailable();
    var fds = new ArrayList<Integer>();
    try {
      int fd = Api.LIB.open(root.toString(), DIRECTORY | NOFOLLOW | CLOEXEC);
      if (fd < 0) throw SecureFileAccess.unavailable();
      fds.add(fd);
      samePath(root, stat(fd), DIR);
      Path relative = root.relativize(file), live = root;
      for (int i = 0; i < relative.getNameCount(); i++) {
        boolean leaf = i == relative.getNameCount() - 1;
        fd =
            Api.LIB.openat(
                fd,
                relative.getName(i).toString(),
                NOFOLLOW | CLOEXEC | NONBLOCK | (leaf ? 0 : DIRECTORY));
        if (fd < 0) throw SecureFileAccess.unavailable();
        fds.add(fd);
        live = live.resolve(relative.getName(i));
        samePath(live, stat(fd), leaf ? REGULAR : DIR);
      }
      Stat before = stat(fd);
      if (before.size > limit) throw new SecureFileAccess.TooLargeException();
      byte[] bytes;
      try (var buffer = new Memory((long) limit + 1);
          var out = new ByteArrayOutputStream()) {
        while (out.size() <= limit) {
          long n =
              Api.LIB.read(fd, buffer, new NativeLong((long) limit + 1 - out.size())).longValue();
          if (n < 0) throw SecureFileAccess.unavailable();
          if (n == 0) break;
          if (n > (long) limit + 1 - out.size()) throw SecureFileAccess.unavailable();
          out.write(buffer.getByteArray(0, (int) n));
        }
        bytes = out.toByteArray();
      }
      if (bytes.length > limit) throw new SecureFileAccess.TooLargeException();
      Stat after = stat(fd);
      if (!before.equals(after) || bytes.length != after.size) throw SecureFileAccess.unavailable();
      samePath(file, after, REGULAR);
      return bytes;
    } finally {
      boolean failed = false;
      for (int i = fds.size() - 1; i >= 0; i--) if (Api.LIB.close(fds.get(i)) != 0) failed = true;
      if (failed) throw SecureFileAccess.unavailable();
    }
  }
}

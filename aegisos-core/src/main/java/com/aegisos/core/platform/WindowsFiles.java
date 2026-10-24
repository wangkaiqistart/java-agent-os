package com.aegisos.core.platform;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Windows本地NTFS句柄实现；目录持有期禁止删除共享，正文同时禁止写共享与删除共享。 */
final class WindowsFiles {
  private WindowsFiles() {}

  private static final int READ = 0x80000000, ATTR = 0x80, OPEN_EXISTING = 3;
  private static final int BACKUP = 0x02000000, OPEN_REPARSE = 0x00200000;
  private static final int DIRECTORY = 0x10, REPARSE = 0x400, DISK = 1;

  interface Kernel extends StdCallLibrary {
    Pointer CreateFileW(
        WString path,
        int access,
        int share,
        Pointer security,
        int disposition,
        int flags,
        Pointer template);

    boolean CloseHandle(Pointer handle);

    boolean GetFileInformationByHandle(Pointer handle, Pointer info);

    int GetFileType(Pointer handle);

    int GetFinalPathNameByHandleW(Pointer handle, char[] buffer, int length, int flags);

    boolean ReadFile(
        Pointer handle, byte[] buffer, int length, IntByReference read, Pointer overlapped);

    int GetDriveTypeW(WString root);

    boolean GetVolumeInformationW(
        WString root,
        char[] label,
        int length,
        IntByReference serial,
        IntByReference maximumComponent,
        IntByReference flags,
        char[] filesystem,
        int fsLength);
  }

  private static final class Api {
    static final Kernel LIB = Native.load("kernel32", Kernel.class);
  }

  record Info(int attrs, long volume, long index, long size, long links, long writeTime) {}

  private static Info info(Pointer handle, boolean directory) throws IOException {
    try (var memory = new Memory(52)) {
      memory.clear();
      if (Api.LIB.GetFileType(handle) != DISK
          || !Api.LIB.GetFileInformationByHandle(handle, memory))
        throw SecureFileAccess.unavailable();
      int attrs = memory.getInt(0);
      if ((attrs & REPARSE) != 0 || ((attrs & DIRECTORY) != 0) != directory)
        throw SecureFileAccess.unavailable();
      long size =
          (Integer.toUnsignedLong(memory.getInt(32)) << 32)
              | Integer.toUnsignedLong(memory.getInt(36));
      long index =
          (Integer.toUnsignedLong(memory.getInt(44)) << 32)
              | Integer.toUnsignedLong(memory.getInt(48));
      if (size < 0) throw SecureFileAccess.unavailable();
      return new Info(
          attrs,
          Integer.toUnsignedLong(memory.getInt(28)),
          index,
          size,
          Integer.toUnsignedLong(memory.getInt(40)),
          memory.getLong(20));
    }
  }

  private static Pointer open(Path path, boolean directory, boolean metadataOnly)
      throws IOException {
    Pointer handle =
        Api.LIB.CreateFileW(
            new WString("\\\\?\\" + path),
            directory || metadataOnly ? ATTR : READ,
            metadataOnly ? 7 : directory ? 3 : 1,
            null,
            OPEN_EXISTING,
            OPEN_REPARSE | (directory ? BACKUP : 0),
            null);
    if (handle == null || Pointer.nativeValue(handle) == -1) throw SecureFileAccess.unavailable();
    return handle;
  }

  private static void samePath(Pointer handle, Path expected) throws IOException {
    char[] chars = new char[32768];
    int n = Api.LIB.GetFinalPathNameByHandleW(handle, chars, chars.length, 0);
    if (n <= 0 || n >= chars.length) throw SecureFileAccess.unavailable();
    String actual = new String(chars, 0, n);
    if (!actual.startsWith("\\\\?\\") || actual.startsWith("\\\\?\\UNC\\"))
      throw SecureFileAccess.unavailable();
    if (!Path.of(actual.substring(4)).equals(expected)) throw SecureFileAccess.unavailable();
  }

  /** 可跨平台测试的Win32名称预检；与原生API分开，不在非Windows加载kernel32。 */
  static void requireSafeNames(String value) throws IOException {
    if (!value.matches("(?i)^[a-z]:[\\\\/].*")
        || value.substring(2).contains(":")
        || value.contains("?")
        || value.contains("\u0000")) throw SecureFileAccess.unavailable();
    // 扩展路径创建保留尾点/空格，而JDBC/Win32可能裁剪；两者不能指向不同inode或锁文件。
    for (String name : value.substring(3).replace('\\', '/').split("/")) {
      if (name.endsWith(".") || name.endsWith(" ")) throw SecureFileAccess.unavailable();
      String base = name.split("\\.", 2)[0].stripTrailing();
      if (base.matches("(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])"))
        throw SecureFileAccess.unavailable();
    }
  }

  /** 拒绝UNC/远程盘/设备路径/ADS；仅接受明确的本地盘符绝对路径和NTFS。 */
  static void localVolume(Path file) throws IOException {
    String value = file.toString();
    if (!file.isAbsolute()) throw SecureFileAccess.unavailable();
    requireSafeNames(value);
    Path volumeRoot = file.getRoot();
    if (volumeRoot == null) throw SecureFileAccess.unavailable();
    WString root = new WString(volumeRoot.toString());
    if (Api.LIB.GetDriveTypeW(root) != 3) throw SecureFileAccess.unavailable();
    char[] fs = new char[32];
    if (!Api.LIB.GetVolumeInformationW(
            root,
            null,
            0,
            new IntByReference(),
            new IntByReference(),
            new IntByReference(),
            fs,
            fs.length)
        || !Native.toString(fs).equalsIgnoreCase("NTFS")) throw SecureFileAccess.unavailable();
  }

  static byte[] read(Path root, Path file, int limit) throws IOException {
    if (Native.POINTER_SIZE != 8) throw SecureFileAccess.unavailable();
    localVolume(file);
    var handles = new ArrayList<Pointer>();
    try {
      // 从盘根开始逐级持有句柄，避免批准根路径中的中间reparse点或目录替换绕过。
      Path current = file.getRoot();
      var paths = new ArrayList<Path>();
      paths.add(current);
      for (Path part : file) {
        current = current.resolve(part);
        paths.add(current);
      }
      Pointer leaf = null;
      for (int i = 0; i < paths.size(); i++) {
        boolean directory = i < paths.size() - 1;
        leaf = open(paths.get(i), directory, false);
        handles.add(leaf);
        info(leaf, directory);
        samePath(leaf, paths.get(i));
      }
      Info before = info(leaf, false);
      if (before.size > limit) throw new SecureFileAccess.TooLargeException();
      byte[] buffer = new byte[Math.min(limit + 1, 8192)];
      byte[] bytes;
      try (var out = new ByteArrayOutputStream()) {
        while (out.size() <= limit) {
          int wanted = Math.min(buffer.length, limit + 1 - out.size());
          var n = new IntByReference();
          if (!Api.LIB.ReadFile(leaf, buffer, wanted, n, null)
              || n.getValue() < 0
              || n.getValue() > wanted) throw SecureFileAccess.unavailable();
          if (n.getValue() == 0) break;
          out.write(buffer, 0, n.getValue());
        }
        bytes = out.toByteArray();
      }
      if (bytes.length > limit) throw new SecureFileAccess.TooLargeException();
      Info after = info(leaf, false);
      if (!before.equals(after) || bytes.length != after.size) throw SecureFileAccess.unavailable();
      samePath(leaf, file);
      return bytes;
    } finally {
      boolean failed = false;
      for (int i = handles.size() - 1; i >= 0; i--)
        if (!Api.LIB.CloseHandle(handles.get(i))) failed = true;
      if (failed) throw SecureFileAccess.unavailable();
    }
  }

  static void requireSingleLink(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    localVolume(absolute);
    Pointer handle = open(absolute, false, true);
    try {
      if (info(handle, false).links != 1) throw SecureFileAccess.unavailable();
      samePath(handle, absolute);
    } finally {
      if (!Api.LIB.CloseHandle(handle)) throw SecureFileAccess.unavailable();
    }
  }
}

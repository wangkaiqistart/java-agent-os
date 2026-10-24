package com.aegisos.storage.runtime;

import com.aegisos.core.agent.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * 数据源创建前获取本机独占锁。解析父目录和已存在数据库的真实路径，避免相对路径/软链接别名绕过。 生命周期只锁独立
 * sidecar；数据库必须是可验证的单链接普通文件，否则拒绝启动。不支持网络共享文件系统。
 */
public final class RuntimeDatabaseLock implements AutoCloseable {
  private static final Set<Path> OWNED_SIDECARS = new HashSet<>();
  private final List<FileChannel> channels = new ArrayList<>();
  private final List<FileLock> locks = new ArrayList<>();
  private Path ownedSidecar;

  public static RuntimeDatabaseLock acquire(Path database) {
    var result = new RuntimeDatabaseLock();
    try {
      Path normalized = database.toAbsolutePath().normalize();
      Path parent = normalized.getParent();
      if (parent == null || normalized.getFileName() == null)
        throw new com.aegisos.core.audit.AuditUnavailableException();
      Files.createDirectories(parent);
      Path real =
          Files.exists(normalized)
              ? normalized.toRealPath()
              : parent.toRealPath().resolve(normalized.getFileName());
      // .lock 保留给独占锁，不能让另一数据源将它作为数据库打开/关闭并释放首个持有者的
      // POSIX 锁。按规范目标检查同时拦截软链接别名；大小写统一以覆盖不区分大小写的文件系统。
      Path filename = real.getFileName();
      if (filename == null || filename.toString().toLowerCase(Locale.ROOT).endsWith(".lock"))
        throw new com.aegisos.core.audit.AuditUnavailableException();
      result.lock(real.resolveSibling(filename + ".lock"));
      // SQLite/JDBC 会独立打开和关闭数据库文件；在 POSIX 上，这会释放同进程对该 inode 的
      // record locks，不能用数据库 inode 锁保证生命周期排他。独立 sidecar 不受连接池轮换影响。
      // 硬链接有不同的规范路径/sidecar，因此在任何 SQLite 连接或恢复动作之前一律拒绝。
      requireSingleLinkDatabase(real);
      return result;
    } catch (OverlappingFileLockException e) {
      result.close();
      throw new AgentException(AgentErrorCode.WORKSPACE_IN_USE);
    } catch (AgentException e) {
      result.close();
      throw e;
    } catch (IOException | RuntimeException e) {
      result.close();
      throw new com.aegisos.core.audit.AuditUnavailableException();
    }
  }

  private static void requireSingleLinkDatabase(Path database) throws IOException {
    try {
      try {
        Files.createFile(
            database,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException e) {
        Files.createFile(database);
      }
    } catch (FileAlreadyExistsException ignored) {
      // 只检查已有文件，不截断、不修改其权限；旧版本数据库仍由 schema 门槛决定是否可启动。
    }
    requireSingleLinkFile(database);
    // 无 unix:nlink 的文件系统会抛出异常并安全拒绝，不能把“无法验证”当成单链接。
    // 工作区仍由可信管理员维护；不承诺防御同等 OS 权限下并发重命名/删除锁文件的恶意写者。
  }

  private static void requireSingleLinkFile(Path database) throws IOException {
    var attributes =
        Files.readAttributes(database, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object count = Files.getAttribute(database, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || !(count instanceof Number links) || links.longValue() != 1)
      throw new com.aegisos.core.audit.AuditUnavailableException();
  }

  private void lock(Path path) throws IOException {
    // FileLock 的 JVM 内重叠检测发生在 channel 打开之后；失败时关闭第二个 fd 也会释放
    // 第一持有者的 POSIX 锁。因此必须先在 JVM 内认领规范路径，再打开唯一的 sidecar channel。
    synchronized (OWNED_SIDECARS) {
      if (!OWNED_SIDECARS.add(path)) throw new AgentException(AgentErrorCode.WORKSPACE_IN_USE);
      ownedSidecar = path;
    }
    // 已存在的多硬链接 sidecar 必须在打开前拒绝；打开后再拒绝并 close 已足以释放
    // 当前进程从另一规范路径持有的同 inode POSIX 锁。打开后的复核仍保留，不作并发替换保证。
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requireSingleLinkFile(path);
    Set<OpenOption> options =
        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileChannel channel;
    try {
      channel =
          FileChannel.open(
              path,
              options,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      channel = FileChannel.open(path, options);
    }
    channels.add(channel);
    requireSingleLinkFile(path);
    FileLock lock = channel.tryLock();
    if (lock == null) throw new AgentException(AgentErrorCode.WORKSPACE_IN_USE);
    locks.add(lock);
  }

  @Override
  public void close() {
    for (var lock : locks)
      try {
        lock.release();
      } catch (IOException ignored) {
      }
    for (var channel : channels)
      try {
        channel.close();
      } catch (IOException ignored) {
      }
    locks.clear();
    channels.clear();
    synchronized (OWNED_SIDECARS) {
      if (ownedSidecar != null) OWNED_SIDECARS.remove(ownedSidecar);
      ownedSidecar = null;
    }
  }
}

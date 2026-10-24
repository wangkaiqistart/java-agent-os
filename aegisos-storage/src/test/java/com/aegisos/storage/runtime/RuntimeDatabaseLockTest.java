package com.aegisos.storage.runtime;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeDatabaseLockTest {
  @TempDir Path dir;

  @Test
  void refusesHardLinkedDatabaseBeforeTakingOwnership() throws Exception {
    Path db = Files.createFile(dir.resolve("hard-linked.sqlite"));
    Path alias = Files.createLink(dir.resolve("other-name.sqlite"), db);
    byte[] before = Files.readAllBytes(db);
    for (Path path : java.util.List.of(db, alias)) {
      assertThatThrownBy(
              () -> {
                try (var ignored = RuntimeDatabaseLock.acquire(path)) {}
              })
          .isInstanceOf(com.aegisos.core.audit.AuditUnavailableException.class)
          .hasMessage("AUDIT_UNAVAILABLE")
          .hasNoCause();
    }
    assertThat(Files.readAllBytes(db)).containsExactly(before);
  }

  /** 数据库不能复用另一库的 sidecar；软链接别名也必须按规范目标检查。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusesDatabaseThatIsAnotherOwnersSidecar(boolean symbolicAlias) throws Exception {
    Path db = dir.resolve("owner.sqlite");
    try (var owner = RuntimeDatabaseLock.acquire(db)) {
      Path sidecar = dir.resolve("owner.sqlite.lock");
      Path candidate = sidecar;
      if (symbolicAlias) {
        candidate = Files.createSymbolicLink(dir.resolve("innocent.sqlite"), sidecar);
      }
      Path target = candidate;
      assertThatThrownBy(
              () -> {
                try (var unexpected = RuntimeDatabaseLock.acquire(target)) {}
              })
          .isInstanceOf(com.aegisos.core.audit.AuditUnavailableException.class)
          .hasMessage("AUDIT_UNAVAILABLE")
          .hasNoCause();
      assertThat(Files.size(sidecar)).isZero();
      assertThat(Files.exists(dir.resolve("owner.sqlite.lock.lock"))).isFalse();
      assertThat(probe(db)).isEqualTo(3);
    }
  }

  @Test
  void rejectedHardLinkedSidecarCannotReleaseExistingOwnersLock() throws Exception {
    Path db = dir.resolve("owner.sqlite");
    try (var owner = RuntimeDatabaseLock.acquire(db)) {
      Path alias =
          Files.createLink(dir.resolve("other.sqlite.lock"), dir.resolve("owner.sqlite.lock"));
      assertThatThrownBy(() -> RuntimeDatabaseLock.acquire(dir.resolve("other.sqlite")))
          .isInstanceOf(com.aegisos.core.audit.AuditUnavailableException.class);
      // 删除测试别名后恢复 nlink=1；只有确实保住原进程锁，独立探针才会返回占用而非成功。
      Files.delete(alias);
      assertThat(probe(db)).isEqualTo(3);
    }
  }

  @Test
  void aliasesCannotBypassLockAndCloseReleases() throws Exception {
    Path db = dir.resolve("db.sqlite");
    Files.createFile(db);
    Path alias = dir.resolve("alias");
    Files.createSymbolicLink(alias, db);
    try (var lock = RuntimeDatabaseLock.acquire(db)) {
      assertThatThrownBy(() -> RuntimeDatabaseLock.acquire(alias)).hasMessage("WORKSPACE_IN_USE");
      assertThatThrownBy(() -> RuntimeDatabaseLock.acquire(dir.resolve("sub/../db.sqlite")))
          .hasMessage("WORKSPACE_IN_USE");
      // 同 JVM 的竞争失败也不能关闭同一 sidecar 的另一个 fd，释放第一持有者的 POSIX 锁。
      assertThat(probe(db)).isEqualTo(3);
    }
    try (var lock = RuntimeDatabaseLock.acquire(alias)) {
      assertThat(lock).isNotNull();
    }
  }

  @Test
  void separateProcessIsExcludedBeforeRecovery() throws Exception {
    Path db = dir.resolve("separate.sqlite");
    try (var lock = RuntimeDatabaseLock.acquire(db)) {
      var child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  Probe.class.getName(),
                  db.toString())
              .redirectErrorStream(true)
              .start();
      assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
      String output =
          new String(
              child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(child.exitValue()).as(output).isEqualTo(3);
    }
  }

  public static final class Probe {
    public static void main(String[] args) {
      try (var lock = RuntimeDatabaseLock.acquire(Path.of(args[0]))) {
        System.exit(0);
      } catch (com.aegisos.core.agent.AgentException e) {
        System.exit(e.code() == com.aegisos.core.agent.AgentErrorCode.WORKSPACE_IN_USE ? 3 : 5);
      } catch (com.aegisos.core.audit.AuditUnavailableException e) {
        System.exit(5);
      }
    }
  }

  /** 返回严格的探针退出码；类加载/意外异常产生的 1 不能冒充“成功阻止第二进程”。 */
  static int probe(Path database) throws Exception {
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Probe.class.getName(),
                database.toString())
            .redirectErrorStream(true)
            .start();
    try {
      assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
      return child.exitValue();
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }
}

package com.aegisos.storage.runtime;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.storage.audit.AuditStorageConfiguration;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStorageLifecycleTest {
  @TempDir Path root;

  /** 强制真实连接池更换物理 SQLite 连接，避免等默认 maxLifetime，也不以同 JVM 锁表代替跨进程。 */
  @Test
  void poolConnectionReplacementCannotEnableHardLinkSecondProcess() throws Exception {
    Path db = root.resolve("rotation.db");
    Path alias = root.resolve("rotation-alias.db");
    var configuration = new AuditStorageConfiguration();
    try (var first = configuration.dataSource(db.toString(), 100)) {
      org.sqlite.SQLiteConnection old;
      try (var connection = first.getConnection();
          var statement = connection.createStatement()) {
        old = connection.unwrap(org.sqlite.SQLiteConnection.class);
        statement.execute(
            "INSERT INTO llm_calls(id,request_id,attempt_no,status,created_at,retryable) "
                + "VALUES('rotation-audit','rotation-request',1,'STARTED','2026-10-22T00:00:00Z',0)");
        first.getHikariPoolMXBean().softEvictConnections();
      }
      try (var replacement = first.getConnection()) {
        assertThat(replacement.unwrap(org.sqlite.SQLiteConnection.class)).isNotSameAs(old);
        assertThat(old.isClosed()).isTrue();
      }
      assertThat(RuntimeDatabaseLockTest.probe(db)).isEqualTo(3);
      // 启动后出现硬链接，第二进程也必须在打开 SQLite/执行恢复之前拒绝。
      Files.createLink(alias, db);
      try {
        assertThat(RuntimeDatabaseLockTest.probe(alias)).isEqualTo(5);
        try (var connection = first.getConnection();
            var statement = connection.createStatement();
            var rows =
                statement.executeQuery("SELECT status FROM llm_calls WHERE id='rotation-audit'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("STARTED");
        }
      } finally {
        Files.delete(alias);
      }
    }
    try (var reopened = configuration.dataSource(db.toString(), 100)) {
      assertThat(reopened.isClosed()).isFalse();
    }
  }

  @Test
  void dataSourceOwnsLockAndVerifiesEveryBorrowedConnection() throws Exception {
    String path = root.resolve("runtime.db").toString();
    var configuration = new AuditStorageConfiguration();
    try (var first = configuration.dataSource(path, 100)) {
      try (var connection = first.getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("PRAGMA foreign_keys")) {
        assertThat(result.getInt(1)).isEqualTo(1);
      }
      assertThatThrownBy(() -> configuration.dataSource(path, 100)).hasMessage("WORKSPACE_IN_USE");
      try (var connection = first.getConnection();
          var statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys=OFF");
      }
      assertThatThrownBy(first::getConnection).hasMessage("FOREIGN_KEYS_UNAVAILABLE");
    }
    try (var reopened = configuration.dataSource(path, 100);
        var connection = reopened.getConnection()) {
      assertThat(connection.isClosed()).isFalse();
    }
  }
}

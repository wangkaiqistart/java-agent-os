package com.aegisos.storage.audit;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

class AuditStorageFailureTest {
  @TempDir Path dir;

  AnnotationConfigApplicationContext open(Path db) {
    var c = new AnnotationConfigApplicationContext();
    c.getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "aegisos.audit.path", db.toString(), "aegisos.audit.busy-timeout-ms", "100")));
    c.register(AuditStorageConfiguration.class);
    c.refresh();
    return c;
  }

  AuditStart start() {
    return new AuditStart(UUID.randomUUID(), 1, "p", "m", Instant.now());
  }

  @Test
  void realWriteLockFailsWithinBusyTimeoutAndDoesNotLoseOldRows() throws Exception {
    Path db = dir.resolve("locked.db");
    try (var c = open(db)) {
      var store = c.getBean(LlmCallAuditStore.class);
      store.reject(
          new AuditRejection(
              UUID.randomUUID(), null, null, Instant.now(), ProviderErrorCode.PROVIDER_REQUIRED));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var sql = connection.createStatement()) {
        sql.execute("BEGIN EXCLUSIVE");
        long time = System.nanoTime();
        assertThatThrownBy(() -> store.begin(start()))
            .isInstanceOf(AuditUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - time)).isLessThan(Duration.ofSeconds(3));
        sql.execute("ROLLBACK");
        try (var rows = sql.executeQuery("SELECT count(*) FROM llm_calls")) {
          assertThat(rows.getInt(1)).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void beginLeavesNoLongTransactionAndConnectionFailureIsSafe() throws Exception {
    Path db = dir.resolve("short.db");
    try (var c = open(db)) {
      var store = c.getBean(LlmCallAuditStore.class);
      store.begin(start());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var sql = connection.createStatement()) {
        sql.executeUpdate("UPDATE llm_calls SET provider_name='changed'");
      }
      c.getBean(com.zaxxer.hikari.HikariDataSource.class).close();
      assertThatThrownBy(() -> store.begin(start()))
          .isInstanceOfSatisfying(
              AuditUnavailableException.class,
              e -> {
                assertThat(e.getCause()).isNull();
                assertThat(e.getMessage()).isEqualTo("AUDIT_UNAVAILABLE");
              });
    }
  }

  @Test
  void commitFailureRollsBackAndCannotPretendBeginOrCompletionCommitted() throws Exception {
    Path db = dir.resolve("commit.db");
    try (var c = open(db)) {
      var emf = c.getBean(EntityManagerFactory.class);
      var tm =
          new JpaTransactionManager(emf) {
            @Override
            protected void doCommit(DefaultTransactionStatus s) {
              throw new TransactionSystemException("injected commit failure");
            }
          };
      tm.setRollbackOnCommitFailure(true);
      var broken = new JpaLlmCallAuditStore(emf, tm);
      assertThatThrownBy(() -> broken.begin(start())).isInstanceOf(AuditUnavailableException.class);
      var store = c.getBean(LlmCallAuditStore.class);
      var h = store.begin(start());
      assertThatThrownBy(
              () ->
                  broken.complete(
                      h,
                      new AuditCompletion(
                          AuditStatus.SUCCEEDED, Instant.now(), 0, null, null, false)))
          .isInstanceOf(AuditUnavailableException.class);
    }
    try (var c = open(db);
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var sql = connection.createStatement();
        var rows = sql.executeQuery("SELECT status FROM llm_calls")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("STARTED");
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void invalidDirectoryAndReadonlyDatabaseNeverFallbackToMemory() throws Exception {
    Path regularFile = dir.resolve("not-directory");
    Files.writeString(regularFile, "x");
    assertThatThrownBy(
            () ->
                new AuditStorageConfiguration()
                    .dataSource(regularFile.resolve("audit.db").toString(), 100))
        .isInstanceOf(AuditUnavailableException.class);
    Path db = dir.resolve("readonly.db");
    try (var c = open(db)) {
      c.getBean(LlmCallAuditStore.class).begin(start());
    }
    try (var ds = new com.zaxxer.hikari.HikariDataSource()) {
      ds.setJdbcUrl("jdbc:sqlite:file:" + db + "?mode=ro");
      ds.setMaximumPoolSize(1);
      // 初始化不能通过忽略写失败而宣称可用；实际写入直接验证文件只读约束。
      try (var connection = ds.getConnection();
          var sql = connection.createStatement()) {
        assertThatThrownBy(() -> sql.executeUpdate("DELETE FROM llm_calls"))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  @Test
  void newlyCreatedRuntimeDirectoryUsesOwnerOnlyPermissions() throws Exception {
    Path parent = dir.resolve("private-runtime");
    try (var c = open(parent.resolve("audit.db"))) {
      if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
        assertThat(Files.getPosixFilePermissions(parent))
            .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
  }
}

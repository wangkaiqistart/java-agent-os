package com.aegisos.storage.audit;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 同一 SQL 同时用于 JDBC 与人工 CLI；失败必须关闭连接并重开验证 V1。 */
class AuditSchemaMigrationTest {
  @TempDir Path dir;

  Path db() {
    return dir.resolve("old.db");
  }

  Connection connection() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + db());
  }

  void migrate() throws Exception {
    try (var c = connection()) {
      try {
        ScriptUtils.executeSqlScript(
            c, new ClassPathResource("db/migration/sqlite/V002__provider_course_audit.sql"));
      } catch (RuntimeException e) {
        try (var s = c.createStatement()) {
          s.execute("ROLLBACK");
        }
        throw e;
      }
    }
  }

  @Test
  void startupRefusesV1WithoutChangingAnything() throws Exception {
    try (var c = connection()) {
      AuditMigrationFixture.createV1(c);
    }
    byte[] before = Files.readAllBytes(db());
    assertThatThrownBy(
            () ->
                AuditSchemaInitializer.initialize(
                    new DriverManagerDataSource("jdbc:sqlite:" + db())))
        .hasMessage("MIGRATION_REQUIRED");
    assertThat(Files.readAllBytes(db())).isEqualTo(before);
  }

  @Test
  void offlineMigrationPreservesRowsAndRerunDoesNotModify() throws Exception {
    java.util.List<java.util.List<Object>> old;
    try (var c = connection()) {
      AuditMigrationFixture.createV1(c);
      old = AuditMigrationFixture.oldRows(c);
    }
    migrate();
    try (var c = connection();
        var s = c.createStatement()) {
      assertThat(AuditMigrationFixture.oldRows(c)).isEqualTo(old);
      try (var r =
          s.executeQuery(
              "SELECT count(*) FROM llm_calls WHERE session_id IS NULL AND ((status='STARTED' AND success IS NULL AND error_message IS NULL) OR (status='SUCCEEDED' AND success=1 AND error_message IS NULL) OR (status IN ('FAILED','REJECTED') AND success=0 AND error_message='历史调用未成功，详细原因不可用'))")) {
        assertThat(r.getInt(1)).isEqualTo(4);
      }
    }
    byte[] before = Files.readAllBytes(db());
    assertThatThrownBy(this::migrate).rootCause().hasMessageContaining("ALREADY_MIGRATED");
    assertThat(Files.readAllBytes(db())).isEqualTo(before);
    AuditSchemaInitializer.initialize(new DriverManagerDataSource("jdbc:sqlite:" + db()));
  }

  /** 已知 DDL 也可能有被绕过约束的坏行；复制失败必须回滚整个事务。 */
  @Test
  void invalidRowCopyRollsBackAndBadVersionNeverInitializes() throws Exception {
    try (var c = connection();
        var s = c.createStatement()) {
      AuditMigrationFixture.createV1(c);
      s.execute("PRAGMA ignore_check_constraints=ON");
      s.execute("UPDATE llm_calls SET input_tokens=-1");
    }
    assertThatThrownBy(this::migrate).isInstanceOf(RuntimeException.class);
    try (var c = connection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT version FROM aegisos_schema")) {
      assertThat(r.getInt(1)).isEqualTo(1);
      assertThat(AuditMigrationFixture.oldRows(c)).hasSize(4);
    }
    Files.delete(db());
    try (var c = connection();
        var s = c.createStatement()) {
      s.execute("CREATE TABLE unrelated(x TEXT)");
    }
    byte[] before = Files.readAllBytes(db());
    assertThatThrownBy(
            () ->
                AuditSchemaInitializer.initialize(
                    new DriverManagerDataSource("jdbc:sqlite:" + db())))
        .isInstanceOf(AuditUnavailableException.class);
    assertThat(Files.readAllBytes(db())).isEqualTo(before);
  }

  @Test
  void unexpectedExtensionObjectsRefuseAndRollback() throws Exception {
    for (String extension :
        java.util.List.of(
            "CREATE INDEX custom_idx ON llm_calls(model_name)",
            "CREATE TRIGGER custom_trigger AFTER INSERT ON llm_calls BEGIN SELECT 1; END")) {
      Files.deleteIfExists(db());
      try (var c = connection();
          var s = c.createStatement()) {
        AuditMigrationFixture.createV1(c);
        s.execute(extension);
      }
      assertThatThrownBy(this::migrate).isInstanceOf(RuntimeException.class);
      try (var c = connection();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT version FROM aegisos_schema")) {
        assertThat(r.getInt(1)).isEqualTo(1);
        assertThat(AuditMigrationFixture.oldRows(c)).hasSize(4);
      }
    }
  }
}

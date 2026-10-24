package com.aegisos.storage.runtime;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.storage.audit.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ReactSchemaMigrationTest {
  @TempDir Path dir;

  void v2(Path path) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var s = c.createStatement()) {
      ScriptUtils.executeSqlScript(
          c, new ClassPathResource("db/schema/sqlite/V002__llm_calls.sql"));
      s.execute(
          "create table aegisos_schema(id integer primary key check(id=1),version integer not null)");
      s.execute("insert into aegisos_schema values(1,2)");
      s.execute(
          "insert into llm_calls(id,request_id,attempt_no,status,created_at,retryable) values('audit-id','request-id',1,'STARTED','2026-10-22T00:00:00Z',0)");
    }
  }

  void migrate(Path path) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
      try {
        ScriptUtils.executeSqlScript(
            c, new ClassPathResource("db/migration/sqlite/V003__react_runtime.sql"));
      } catch (RuntimeException e) {
        try (var s = c.createStatement()) {
          s.execute("ROLLBACK");
        }
        throw e;
      }
    }
  }

  @Test
  void requiresExplicitMigrationAndPreservesEveryOldColumn() throws Exception {
    Path path = dir.resolve("v2.db");
    v2(path);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + path);
    assertThatThrownBy(() -> AuditSchemaInitializer.initialize(ds))
        .hasMessage("MIGRATION_REQUIRED");
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("create temporary table old_audit as select * from llm_calls");
    }
    // 完整旧行比较使用独立内存副本；迁移关闭重开，避免连接局部 TEMP 表造成假验证。
    List<Object> before = row(ds);
    migrate(path);
    assertThat(row(ds)).isEqualTo(before);
    AuditSchemaInitializer.initialize(ds);
    byte[] bytes = Files.readAllBytes(path);
    assertThatThrownBy(() -> migrate(path)).isInstanceOf(RuntimeException.class);
    assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
  }

  List<Object> row(DriverManagerDataSource ds) throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("select * from llm_calls")) {
      r.next();
      var row = new ArrayList<Object>();
      for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getObject(i));
      return row;
    }
  }

  @Test
  void failureAfterCreatingTablesRollsBackNewObjectsAndOldAuditIsUnchanged() throws Exception {
    Path path = dir.resolve("midway.db");
    v2(path);
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + path);
    var old = row(ds);
    String original;
    try (var input =
        new ClassPathResource("db/migration/sqlite/V003__react_runtime.sql").getInputStream()) {
      original = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    String broken =
        original.replace(
            "UPDATE aegisos_schema SET version=3",
            "INSERT INTO deliberately_missing_table VALUES(1); UPDATE aegisos_schema SET version=3");
    try (var connection = ds.getConnection()) {
      assertThatThrownBy(
              () ->
                  ScriptUtils.executeSqlScript(
                      connection,
                      new org.springframework.core.io.ByteArrayResource(
                          broken.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
          .isInstanceOf(RuntimeException.class);
      try (var statement = connection.createStatement()) {
        statement.execute("ROLLBACK");
      }
    }
    assertThat(row(ds)).isEqualTo(old);
    try (var connection = ds.getConnection();
        var statement = connection.createStatement()) {
      try (var result = statement.executeQuery("SELECT version FROM aegisos_schema")) {
        assertThat(result.getInt(1)).isEqualTo(2);
      }
      try (var result =
          statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='sessions'")) {
        assertThat(result.getInt(1)).isZero();
      }
    }
  }

  @Test
  void forgedOrPartialSchemaRollsBackEverything() throws Exception {
    Path path = dir.resolve("bad.db");
    v2(path);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var s = c.createStatement()) {
      s.execute("drop index idx_llm_calls_request");
    }
    assertThatThrownBy(() -> migrate(path)).isInstanceOf(RuntimeException.class);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var s = c.createStatement();
        var r = s.executeQuery("select version from aegisos_schema")) {
      assertThat(r.getInt(1)).isEqualTo(2);
    }
  }
}

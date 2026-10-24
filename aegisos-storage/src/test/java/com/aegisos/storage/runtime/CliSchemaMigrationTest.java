package com.aegisos.storage.runtime;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.storage.audit.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CliSchemaMigrationTest {
  @TempDir Path root;

  @Test
  void newDatabaseIsV4AndEnforcesBindingOwnership() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("v4.db"));
    AuditSchemaInitializer.initialize(ds);
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var rs = s.executeQuery("select version from aegisos_schema")) {
      assertThat(rs.getInt(1)).isEqualTo(4);
    }
  }

  @Test
  void bindingConstraintsRejectCrossProfileAndSecondIdentity() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("ownership.db"));
    AuditSchemaInitializer.initialize(ds);
    try (var c = ds.getConnection();
        var q = c.createStatement()) {
      q.execute("PRAGMA foreign_keys=ON");
      String id = java.util.UUID.randomUUID().toString();
      q.execute("insert into sessions values('" + id + "','p','now','now',0,1)");
      q.execute("insert into session_bindings values('cli','local','p','" + id + "','now')");
      assertThatThrownBy(
              () ->
                  q.execute(
                      "insert into session_bindings values('cli','other','p','" + id + "','now')"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  q.execute(
                      "insert into session_bindings values('cli','local','q','"
                          + java.util.UUID.randomUUID()
                          + "','now')"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () ->
                  q.execute(
                      "insert into session_bindings values('cli','bad value','p','"
                          + java.util.UUID.randomUUID()
                          + "','now')"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }
}

package com.aegisos.storage.runtime;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.storage.audit.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReactSchemaTest {
  @TempDir Path dir;

  @Test
  void emptyDatabaseHasCompleteV3AndFkChecks() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("empty.db"));
    AuditSchemaInitializer.initialize(ds);
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      try (var r = s.executeQuery("select version from aegisos_schema")) {
        assertThat(r.getInt(1)).isEqualTo(3);
      }
      var tables = new HashSet<String>();
      try (var r = s.executeQuery("select name from sqlite_master where type='table'")) {
        while (r.next()) tables.add(r.getString(1));
      }
      assertThat(tables)
          .contains(
              "sessions",
              "agent_processings",
              "model_turns",
              "llm_request_links",
              "message_exchange_groups",
              "session_messages",
              "tool_invocations",
              "processing_events",
              "llm_calls");
      s.execute("PRAGMA foreign_keys=ON");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into agent_processings(id,session_id,runtime_instance_id,status,started_at,iteration_count,version) values('p','missing','r','RUNNING','now',0,0)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void partialOrForgedV3IsRefused() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("forged.db"));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("create table aegisos_schema(id integer primary key,version integer)");
      s.execute("insert into aegisos_schema values(1,3)");
      s.execute("create table sessions(id text)");
    }
    assertThatThrownBy(() -> AuditSchemaInitializer.initialize(ds)).hasMessage("AUDIT_UNAVAILABLE");
  }

  @Test
  void wrongGroupOwnershipIsRejectedByDatabase() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("ownership.db"));
    AuditSchemaInitializer.initialize(ds);
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("insert into sessions values('s1','profile','now','now',0,1)");
      s.execute("insert into sessions values('s2','profile','now','now',0,1)");
      s.execute(
          "insert into agent_processings values('p1','s1','r','RUNNING','now',null,0,null,0)");
      s.execute("insert into message_exchange_groups values('g','p1',0,'USER','CLOSED',null,0,0)");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into session_messages values('m','s2','p1',1,'g','USER','hello',null,null,'now')"))
          .isInstanceOf(SQLException.class);
    }
  }
}

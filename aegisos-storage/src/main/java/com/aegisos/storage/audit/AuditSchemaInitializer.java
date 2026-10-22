package com.aegisos.storage.audit;

import com.aegisos.core.audit.AuditUnavailableException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 先只读识别，再初始化真正空库；绝不在 V1/未知库上创建对象或自动升级。 */
public final class AuditSchemaInitializer {
  private AuditSchemaInitializer() {}

  private static String normalized(String text) {
    return text.replaceAll("[\\s\"]", "").toLowerCase(Locale.ROOT).replace(";", "");
  }

  public static void initialize(DataSource ds) {
    try (var c = ds.getConnection()) {
      var objects = new HashSet<String>();
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
        while (r.next()) objects.add(r.getString(1));
      }
      if (objects.isEmpty()) {
        c.setAutoCommit(false);
        try {
          ScriptUtils.executeSqlScript(
              c, new ClassPathResource("db/schema/sqlite/V002__llm_calls.sql"));
          try (var s = c.createStatement()) {
            s.execute(
                "CREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            s.execute("INSERT INTO aegisos_schema VALUES(1,2)");
          }
          c.commit();
        } catch (RuntimeException | SQLException e) {
          c.rollback();
          throw new AuditUnavailableException();
        }
      } else {
        int version;
        try (var s = c.createStatement();
            var r = s.executeQuery("SELECT id,version FROM aegisos_schema")) {
          if (!r.next() || r.getInt(1) != 1) throw new AuditUnavailableException();
          version = r.getInt(2);
          if (r.next()) throw new AuditUnavailableException();
        }
        if (version == 1) throw new AuditMigrationRequiredException();
        if (version != 2) throw new AuditUnavailableException();
      }
      validate(c);
    } catch (AuditMigrationRequiredException e) {
      throw e;
    } catch (Exception e) {
      throw new AuditUnavailableException();
    }
  }

  /** 对规范 DDL/索引逐项检查；新增列而遗漏 CHECK 的伪 V2 不能启动。 */
  private static void validate(Connection c) throws Exception {
    String ddl;
    try (var in =
        AuditSchemaInitializer.class.getResourceAsStream("/db/schema/sqlite/V002__llm_calls.sql")) {
      if (in == null) throw new AuditUnavailableException();
      ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var expected = new HashSet<String>();
    for (String part : ddl.split(";")) if (!part.isBlank()) expected.add(normalized(part));
    var actual = new HashSet<String>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT sql FROM sqlite_master WHERE tbl_name='llm_calls' AND sql IS NOT NULL")) {
      while (r.next()) actual.add(normalized(r.getString(1)));
    }
    if (!expected.equals(actual)) throw new AuditUnavailableException();
    try (var s = c.createStatement();
        var r = s.executeQuery("PRAGMA integrity_check")) {
      if (!r.next() || !"ok".equals(r.getString(1)) || r.next())
        throw new AuditUnavailableException();
    }
  }
}

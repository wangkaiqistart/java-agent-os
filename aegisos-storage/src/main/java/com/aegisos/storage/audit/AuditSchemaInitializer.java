package com.aegisos.storage.audit;

import com.aegisos.core.audit.AuditUnavailableException;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 先只读识别，再初始化真正空库；绝不在 V1/未知库上创建对象或自动升级。 */
public final class AuditSchemaInitializer {
  private AuditSchemaInitializer() {}

  public static void initialize(DataSource ds) {
    try (var c = ds.getConnection()) {
      try (var statement = c.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        try (var result = statement.executeQuery("PRAGMA foreign_keys")) {
          if (!result.next() || result.getInt(1) != 1) throw new AuditUnavailableException();
        }
      }
      var objects = new HashSet<String>();
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
        while (r.next()) objects.add(r.getString(1));
      }
      if (objects.isEmpty()) {
        c.setAutoCommit(false);
        try {
          ScriptUtils.executeSqlScript(
              c, new ClassPathResource("db/schema/sqlite/V004__cli_session_bindings.sql"));
          try (var s = c.createStatement()) {
            s.execute(
                "CREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            s.execute("INSERT INTO aegisos_schema VALUES(1,4)");
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
        if (version == 1 || version == 2) throw new AuditMigrationRequiredException();
        if (version == 3) {
          validateExisting(c, 3);
          throw new AuditMigrationRequiredException();
        }
        if (version != 4) throw new AuditUnavailableException();
      }
      validateExisting(c, 4);
    } catch (AuditMigrationRequiredException e) {
      throw e;
    } catch (Exception e) {
      throw new AuditUnavailableException();
    }
  }

  /** 保留现有校验入口；实际算法也供独立只读路径使用。 */
  public static void validateExisting(Connection c, int version) throws Exception {
    ExistingSchemaValidator.validate(c, version);
  }
}

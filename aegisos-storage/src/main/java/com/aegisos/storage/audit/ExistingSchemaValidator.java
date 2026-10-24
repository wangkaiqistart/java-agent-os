package com.aegisos.storage.audit;

import com.aegisos.core.audit.AuditUnavailableException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 仅执行schema/完整性只读验证，不创建、迁移或恢复任何数据。 */
public final class ExistingSchemaValidator {
  private ExistingSchemaValidator() {}

  private static String normalized(String text) {
    return text.replaceAll("[\\s\"]", "").toLowerCase(Locale.ROOT).replace(";", "");
  }

  public static void validate(Connection c, int version) throws Exception {
    if (version != 3 && version != 4) throw new AuditUnavailableException();
    String ddl;
    try (var in =
        ExistingSchemaValidator.class.getResourceAsStream(
            version == 3
                ? "/db/schema/sqlite/V003__react_runtime.sql"
                : "/db/schema/sqlite/V004__cli_session_bindings.sql")) {
      if (in == null) throw new AuditUnavailableException();
      ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    var expected = new HashSet<String>();
    for (String part : ddl.split(";")) if (!part.isBlank()) expected.add(normalized(part));
    var actual = new HashSet<String>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT sql FROM sqlite_master WHERE name<>'aegisos_schema' AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL")) {
      while (r.next()) actual.add(normalized(r.getString(1)));
    }
    try (var st = c.createStatement();
        var rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE name='aegisos_schema'")) {
      if (!rs.next()
          || !normalized(rs.getString(1))
              .equals(
                  normalized(
                      "CREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)")))
        throw new AuditUnavailableException();
    }
    try (var st = c.createStatement();
        var rs = st.executeQuery("PRAGMA foreign_key_check")) {
      if (rs.next()) throw new AuditUnavailableException();
    }
    if (!expected.equals(actual)) throw new AuditUnavailableException();
    try (var s = c.createStatement();
        var r = s.executeQuery("PRAGMA integrity_check")) {
      if (!r.next() || !"ok".equals(r.getString(1)) || r.next())
        throw new AuditUnavailableException();
    }
  }
}

package com.aegisos.storage.audit;

import java.sql.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 用原 V001 创建合成旧库；大整数及未知错误用于检查迁移逐列保真。 */
final class AuditMigrationFixture {
  static void createV1(Connection c) throws SQLException {
    ScriptUtils.executeSqlScript(c, new ClassPathResource("db/schema/sqlite/V001__llm_calls.sql"));
    try (var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE aegisos_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
      s.execute("INSERT INTO aegisos_schema VALUES(1,1)");
      for (String state : List.of("STARTED", "SUCCEEDED", "FAILED", "REJECTED")) {
        boolean pending = state.equals("STARTED"),
            failed = state.equals("FAILED") || state.equals("REJECTED");
        s.execute(
            "INSERT INTO llm_calls VALUES('"
                + state
                + "','req-"
                + state
                + "',"
                + (state.equals("REJECTED") ? 0 : 1)
                + ",'p','m','"
                + state
                + "','2026-01-01',"
                + (pending ? "NULL,NULL" : "'2026-01-02',0")
                + ",4294967296,NULL,4294967296,"
                + (failed ? "'OLD_UNKNOWN'" : "NULL")
                + ",0)");
      }
    }
  }

  static List<List<Object>> oldRows(Connection c) throws SQLException {
    var rows = new ArrayList<List<Object>>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,input_tokens,output_tokens,total_tokens,error_code,retryable FROM llm_calls ORDER BY id")) {
      while (r.next()) {
        var row = new ArrayList<Object>();
        for (int i = 1; i <= 14; i++) row.add(r.getObject(i));
        rows.add(row);
      }
    }
    return rows;
  }
}

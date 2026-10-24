package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.memory.SessionIdentity;
import com.aegisos.core.provider.ExternalOutcome;
import com.aegisos.core.tool.ToolObservation;
import com.aegisos.core.tool.ToolObservationJson;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliSessionPersistenceTest {
  @Test
  void productionMemoryRestoresTupleAndDoesNotClaimLegacyUuid() throws Exception {
    try (var fixture = new ReactRuntimeFixture()) {
      String tuple;
      String legacy;
      try (var runtime = fixture.openRuntime()) {
        tuple =
            runtime
                .memory()
                .getOrCreateSession(new SessionIdentity("cli", "local", "p"))
                .sessionId();
        legacy = runtime.agents().createSession("p").sessionId();
      }
      try (var runtime = fixture.openRuntime()) {
        assertThat(
                runtime
                    .memory()
                    .getOrCreateSession(new SessionIdentity("cli", "local", "p"))
                    .sessionId())
            .isEqualTo(tuple);
        assertThat(runtime.memory().loadSession(legacy, "p").sessionId()).isEqualTo(legacy);
      }
      try (var c = fixture.reopen();
          var s = c.createStatement();
          var rs = s.executeQuery("select count(*) from session_bindings")) {
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }

  /** 生产 Memory 的1000条读取窗口不能被误当成全库仅保留1000条；重启不得截断完整存档。 */
  @Test
  void productionReopenPreservesFullArchiveAndCompleteToolExchangeBeyondReadWindow()
      throws Exception {
    try (var fixture = new ReactRuntimeFixture()) {
      var identity = new SessionIdentity("cli", "local", "p");
      String sid;
      Map<String, List<List<String>>> before;
      try (var runtime = fixture.openRuntime()) {
        sid = runtime.memory().getOrCreateSession(identity).sessionId();
        try (var connection = fixture.reopen()) {
          seedArchive(connection, sid);
          before = snapshot(connection);
        }
      }
      try (var runtime = fixture.openRuntime()) {
        assertThat(runtime.memory().getOrCreateSession(identity).sessionId()).isEqualTo(sid);
        var restored = runtime.memory().loadSession(sid, "p");
        assertThat(restored.messages()).hasSize(1000);
        assertThat(restored.messages().getLast().content()).isEqualTo("tool-final");
        assertThat(restored.messages().get(998).toolCallId()).isEqualTo("course-call");
        assertThat(restored.messages().get(997).toolCalls())
            .singleElement()
            .satisfies(call -> assertThat(call.argumentsJson()).isEqualTo("{}"));
      }
      try (var connection = fixture.reopen()) {
        var after = snapshot(connection);
        assertThat(after).isEqualTo(before);
        assertThat(after.get("session_messages")).hasSize(1006);
        assertThat(after.get("llm_calls")).hasSize(503);
        assertThat(after.get("tool_invocations")).hasSize(1);
        try (var statement = connection.createStatement();
            var rs = statement.executeQuery("PRAGMA foreign_key_check")) {
          assertThat(rs.next()).isFalse();
        }
      }
      assertThat(fixture.physicalSends).hasValue(0);
      assertThat(fixture.httpRequests).hasValue(0);
    }
  }

  private static final String TIME = "2026-10-24T00:00:00Z";

  // 测试数据经实际V4约束写入，不启动ReAct/模型/工具；短事务批量建立完整的已结束存档。
  private static void seedArchive(Connection c, String sid) throws Exception {
    try (var s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
    }
    c.setAutoCommit(false);
    for (int n = 0; n < 502; n++) {
      boolean tool = n == 501;
      String pid = id(), user = id(), text = id();
      insert(
          c,
          "INSERT INTO agent_processings VALUES(?,?,?,'COMPLETED',?,?,?,null,0)",
          pid,
          sid,
          id(),
          TIME,
          TIME,
          tool ? 2 : 1);
      for (int iteration = 1; iteration <= (tool ? 2 : 1); iteration++) {
        String request = id();
        insert(
            c,
            "INSERT INTO model_turns VALUES(?,?,'ACCEPTED',?,?,null)",
            pid,
            iteration,
            TIME,
            TIME);
        insert(c, "INSERT INTO llm_request_links VALUES(?,?,?)", request, pid, iteration);
        insert(
            c,
            "INSERT INTO llm_calls(id,request_id,attempt_no,provider_name,model_name,status,created_at,completed_at,duration_ms,retryable,session_id,success) VALUES(?,?,1,'provider','model','SUCCEEDED',?,?,0,0,?,1)",
            id(),
            request,
            TIME,
            TIME,
            sid);
      }
      insert(
          c,
          "INSERT INTO message_exchange_groups VALUES(?,?,0,'USER','CLOSED',null,0,0)",
          user,
          pid);
      insert(
          c,
          "INSERT INTO message_exchange_groups VALUES(?,?,?,'TEXT','CLOSED',?,0,0)",
          text,
          pid,
          tool ? 2 : 1,
          tool ? 2 : 1);
      message(c, sid, pid, n * 2 + 1, user, "USER", "safe-user-" + n, null, null);
      if (!tool) {
        message(c, sid, pid, n * 2 + 2, text, "ASSISTANT", "safe-reply-" + n, null, null);
      } else {
        String exchange = id();
        UUID audit = UUID.randomUUID();
        insert(
            c,
            "INSERT INTO message_exchange_groups VALUES(?,?,1,'TOOL_EXCHANGE','CLOSED',1,1,1)",
            exchange,
            pid);
        message(
            c,
            sid,
            pid,
            1004,
            exchange,
            "ASSISTANT",
            null,
            null,
            "[{\"id\":\"course-call\",\"name\":\"http_get\",\"argumentsJson\":\"{}\"}]");
        message(
            c,
            sid,
            pid,
            1005,
            exchange,
            "TOOL",
            ToolObservationJson.encode(
                new ToolObservation(
                    audit, "course-call", "http_get", true, null, ExternalOutcome.RESPONDED, "{}")),
            "course-call",
            null);
        message(c, sid, pid, 1006, text, "ASSISTANT", "tool-final", null, null);
        insert(
            c,
            "INSERT INTO tool_invocations VALUES(?,?,?,?,?,?,?,?,'SUCCEEDED',?,?,0,1,null,null,'RESPONDED')",
            audit.toString(),
            sid,
            pid,
            "course-call",
            "http_get",
            1,
            1,
            "0".repeat(64),
            TIME,
            TIME);
      }
    }
    insert(c, "UPDATE sessions SET next_sequence_no=1007 WHERE id=?", sid);
    c.commit();
  }

  private static void message(
      Connection c,
      String sid,
      String pid,
      int sequence,
      String group,
      String role,
      String content,
      String call,
      String tools)
      throws SQLException {
    insert(
        c,
        "INSERT INTO session_messages VALUES(?,?,?,?,?,?,?,?,?,?)",
        id(),
        sid,
        pid,
        sequence,
        group,
        role,
        content,
        call,
        tools,
        TIME);
  }

  private static void insert(Connection c, String sql, Object... values) throws SQLException {
    try (var statement = c.prepareStatement(sql)) {
      for (int n = 0; n < values.length; n++) statement.setObject(n + 1, values[n]);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  /** 包含schema版本、绑定、全部历史和审计的逐列投影，不只比较行数。 */
  private static Map<String, List<List<String>>> snapshot(Connection c) throws SQLException {
    List<String> tables = new ArrayList<>();
    try (var s = c.createStatement();
        var rs =
            s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
      while (rs.next()) tables.add(rs.getString(1));
    }
    var result = new LinkedHashMap<String, List<List<String>>>();
    for (String table : tables) {
      List<List<String>> rows = new ArrayList<>();
      try (var s = c.createStatement();
          var rs = s.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY rowid")) {
        while (rs.next()) {
          List<String> row = new ArrayList<>();
          for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++)
            row.add(rs.getString(column));
          rows.add(row);
        }
      }
      result.put(table, rows);
    }
    return result;
  }
}

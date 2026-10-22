package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 通过生产装配和独立 SQLite 连接检查会话关联，不模拟会话数据库。 */
class ProviderSessionTest {
  @TempDir Path dir;

  @Test
  void newNullEntryNeverFallsBackAndInvalidValuesNeverLeaveProcess() throws Exception {
    Path db = dir.resolve("reject.db");
    try (var stub =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()));
        var context = stub.productionContext(db)) {
      var service = context.getBean(ProviderService.class);
      for (String id :
          Arrays.asList(null, "", " secret-sentinel", "x".repeat(65), "会话", "abc/def")) {
        assertThatThrownBy(() -> service.chat(id, ModelRequest.text("p", "m", "x")))
            .isInstanceOf(ProviderException.class)
            .hasMessage(id == null ? "SESSION_REQUIRED" : "INVALID_SESSION_ID")
            .hasNoCause();
      }
      assertThat(stub.calls).hasValue(0);
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
          var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT count(*) FROM llm_calls WHERE session_id IS NULL AND status='REJECTED' AND attempt_no=0 AND success=0")) {
        assertThat(r.getInt(1)).isEqualTo(6);
      }
    }
  }

  @Test
  void twoSessionsAndLegacyRemainDistinctAfterReopen() throws Exception {
    Path db = dir.resolve("sessions.db");
    var requests = new HashSet<UUID>();
    try (var stub =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()));
        var context = stub.productionContext(db)) {
      var service = context.getBean(ProviderService.class);
      for (String id : List.of("S-A", "S-B", "S-A", "a".repeat(64)))
        requests.add(service.chat(id, ModelRequest.text("p", "m", "x")).requestId());
      requests.add(service.chat(ModelRequest.text("p", "m", "x")).requestId());
      assertThat(requests).hasSize(5);
      assertThat(stub.calls).hasValue(5);
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT session_id,request_id,attempt_no,success,error_message,status FROM llm_calls")) {
      var ids = new ArrayList<String>();
      while (r.next()) {
        ids.add(r.getString(1));
        assertThat(requests).contains(UUID.fromString(r.getString(2)));
        assertThat(r.getInt(3)).isEqualTo(1);
        assertThat(r.getBoolean(4)).isTrue();
        assertThat(r.getString(5)).isNull();
        assertThat(r.getString(6)).isEqualTo("SUCCEEDED");
      }
      assertThat(ids).containsExactlyInAnyOrder("S-A", "S-B", "S-A", "a".repeat(64), null);
    }
  }
}

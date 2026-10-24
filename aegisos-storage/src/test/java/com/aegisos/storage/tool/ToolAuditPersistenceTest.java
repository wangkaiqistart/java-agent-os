package com.aegisos.storage.tool;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.audit.ToolInvocationAuditStore;
import com.aegisos.core.provider.ExternalOutcome;
import com.aegisos.core.tool.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import com.aegisos.storage.session.JpaSessionStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ToolAuditPersistenceTest {
  @TempDir Path root;

  @Test
  void shortTransactionsHavePhysicalFieldsAndCannotCompleteTwice() throws Exception {
    var config = new AuditStorageConfiguration();
    var db = root.resolve("test.db");
    try (var ds = config.dataSource(db.toString(), 1000)) {
      var factory = config.entityManagerFactory(ds);
      factory.afterPropertiesSet();
      try {
        var manager = config.transactionManager(factory.getObject());
        var sessions = new JpaSessionStore(factory.getObject(), manager, Clock.systemUTC());
        var session = sessions.createSession("p");
        var h = sessions.beginProcessing(session.sessionId(), "p", "safe", UUID.randomUUID());
        h = sessions.prepareModelTurn(h, 1);
        try (var c = ds.getConnection();
            var s = c.createStatement()) {
          s.execute("update model_turns set status='ACCEPTED',completed_at='2026-10-22T00:00:00Z'");
        }
        var store = new JpaToolInvocationAuditStore(factory.getObject(), manager);
        var start =
            new ToolInvocationAuditStore.Start(
                UUID.randomUUID(), h, 1, 1, "id", "http_get", "a".repeat(64), Instant.now());
        store.begin(start);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
            var s = c.createStatement();
            var rows =
                s.executeQuery("select status,success,error_message from tool_invocations")) {
          assertThat(rows.getString(1)).isEqualTo("STARTED");
          assertThat(rows.getObject(2)).isNull();
          assertThat(rows.getString(3)).isNull();
        }
        var result = new ToolResult(true, null, ExternalOutcome.RESPONDED, "not-persisted-body");
        store.complete(start.id(), result, Instant.now(), 1);
        assertThat(store.find(h.processId(), "id").orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> store.complete(start.id(), result, Instant.now(), 1))
            .hasMessage("AUDIT_UNAVAILABLE");
        assertThatThrownBy(() -> store.begin(start)).hasMessage("AUDIT_UNAVAILABLE");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
            var s = c.createStatement()) {
          try (var rows =
              s.executeQuery(
                  "select success,error_message,external_outcome from tool_invocations")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.getString(2)).isNull();
            assertThat(rows.getString(3)).isEqualTo("RESPONDED");
          }
          assertThatThrownBy(() -> s.execute("update tool_invocations set success=0"))
              .isInstanceOf(SQLException.class);
        }
      } finally {
        factory.destroy();
      }
    }
  }
}

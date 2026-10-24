package com.aegisos.storage.session;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.memory.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import com.aegisos.storage.runtime.RuntimeDatabaseLock;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlySessionCatalogTest {
  @TempDir Path root;

  Path db() {
    return root.resolve("catalog.db");
  }

  void seed() throws Exception {
    try (var ds = new AuditStorageConfiguration().dataSource(db().toString(), 1000);
        var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "INSERT INTO sessions VALUES('00000000-0000-0000-0000-000000000001','p','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',0,1)");
      s.execute(
          "INSERT INTO sessions VALUES('00000000-0000-0000-0000-000000000002','q','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',0,1)");
      s.execute(
          "INSERT INTO agent_processings VALUES('processing','00000000-0000-0000-0000-000000000001','runtime','RUNNING','2026-01-01T00:00:00Z',NULL,0,NULL,0)");
    }
  }

  Map<String, String> snapshot() throws Exception {
    var result = new TreeMap<String, String>();
    try (var stream = Files.walk(root)) {
      for (var p : stream.filter(Files::isRegularFile).toList())
        result.put(
            root.relativize(p).toString(),
            Base64.getEncoder().encodeToString(Files.readAllBytes(p))
                + Files.getLastModifiedTime(p));
    }
    return result;
  }

  @Test
  void readsOnlySafeProjectionWithStablePaginationAndNoRecoveryOrFileWrites() throws Exception {
    seed();
    var before = snapshot();
    var catalog = new ReadOnlySessionCatalog(db());
    var page = catalog.list(new SessionQuery(null, 1, 0));
    assertThat(page.items()).hasSize(1);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.items().getFirst().sessionId()).endsWith("0001");
    assertThat(page.items().getFirst().messageCount()).isZero();
    assertThat(page.items().getFirst().processingCount()).isEqualTo(1);
    assertThat(catalog.list(new SessionQuery("q", 20, 0)).items()).hasSize(1);
    assertThat(catalog.list(new SessionQuery(null, 1, 1)).items().getFirst().sessionId())
        .endsWith("0002");
    assertThat(snapshot()).isEqualTo(before);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db());
        var s = c.createStatement();
        var r = s.executeQuery("select status from agent_processings")) {
      assertThat(r.getString(1)).isEqualTo("RUNNING");
    }
  }

  @Test
  void missingDatabaseAndLockNeverCreateFiles() throws Exception {
    var empty = snapshot();
    assertThatThrownBy(() -> new ReadOnlySessionCatalog(db()).list(new SessionQuery()))
        .hasMessage("DATABASE_NOT_INITIALIZED");
    assertThat(snapshot()).isEqualTo(empty);
    seed();
    Files.delete(db().resolveSibling("catalog.db.lock"));
    var before = snapshot();
    assertThatThrownBy(() -> new ReadOnlySessionCatalog(db()).list(new SessionQuery()))
        .hasMessage("QUERY_UNAVAILABLE");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void busyOldSchemaAndUnsafeWalAreExplicitFailures() throws Exception {
    seed();
    var catalog = new ReadOnlySessionCatalog(db());
    try (var lock = RuntimeDatabaseLock.acquire(db())) {
      assertThatThrownBy(() -> catalog.list(new SessionQuery())).hasMessage("QUERY_UNAVAILABLE");
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db());
        var s = c.createStatement()) {
      s.execute("UPDATE aegisos_schema SET version=3");
    }
    assertThatThrownBy(() -> catalog.list(new SessionQuery())).hasMessage("MIGRATION_REQUIRED");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db());
        var s = c.createStatement()) {
      s.execute("UPDATE aegisos_schema SET version=4");
      s.execute("PRAGMA journal_mode=WAL");
    }
    var before = snapshot();
    assertThatThrownBy(() -> catalog.list(new SessionQuery())).hasMessage("QUERY_UNAVAILABLE");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void queryAndSummaryValidateBounds() {
    for (int n : new int[] {0, 101})
      assertThatThrownBy(() -> new SessionQuery(null, n, 0)).hasMessage("INVALID_SESSION_QUERY");
    for (int n : new int[] {-1, 100001})
      assertThatThrownBy(() -> new SessionQuery(null, 20, n)).hasMessage("INVALID_SESSION_QUERY");
    assertThatThrownBy(() -> new SessionQuery("../p", 20, 0)).hasMessage("INVALID_SESSION_QUERY");
    assertThatThrownBy(
            () ->
                new SessionSummary(
                    "bad", "p", java.time.Instant.EPOCH, java.time.Instant.EPOCH, 0, 0))
        .hasMessage("INVALID_SESSION_SUMMARY");
  }

  @Test
  void paginationOrdersRealInstantAcrossVariableFractionalPrecision() throws Exception {
    seed();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db());
        var q = c.createStatement()) {
      q.execute("UPDATE sessions SET updated_at='2026-01-02T10:00:00Z'");
      q.execute(
          "INSERT INTO sessions VALUES('00000000-0000-0000-0000-000000000003','p','2026-01-01T00:00:00Z','2026-01-02T10:00:00.001Z',0,1)");
      q.execute(
          "INSERT INTO sessions VALUES('00000000-0000-0000-0000-000000000004','p','2026-01-01T00:00:00Z','2026-01-02T10:00:00.001001Z',0,1)");
    }
    var before = snapshot();
    var catalog = new ReadOnlySessionCatalog(db());
    assertThat(catalog.list(new SessionQuery(null, 1, 0)).items().getFirst().sessionId())
        .endsWith("0004");
    assertThat(catalog.list(new SessionQuery(null, 1, 1)).items().getFirst().sessionId())
        .endsWith("0003");
    assertThat(catalog.list(new SessionQuery(null, 1, 2)).items().getFirst().sessionId())
        .endsWith("0001");
    assertThat(snapshot()).isEqualTo(before);
  }
}

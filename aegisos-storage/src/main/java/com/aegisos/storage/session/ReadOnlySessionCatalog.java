package com.aegisos.storage.session;

import com.aegisos.core.context.DefaultSensitiveContentGuard;
import com.aegisos.core.memory.*;
import com.aegisos.core.memory.SessionQueryException.State;
import com.aegisos.storage.audit.ExistingSchemaValidator;
import com.aegisos.storage.runtime.RuntimeDatabaseLock;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 独立只读适配器；无数据源池/初始化/恢复装配，每次查询拥有自己的短生命周期锁和连接。 */
public final class ReadOnlySessionCatalog implements SessionCatalog {
  private final Path database;

  public ReadOnlySessionCatalog(Path database) {
    this.database = database.toAbsolutePath().normalize();
  }

  public Page list(SessionQuery query) {
    Objects.requireNonNull(query);
    if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS))
      throw new SessionQueryException(State.DATABASE_NOT_INITIALIZED);
    try (var lock = RuntimeDatabaseLock.acquireQuery(database)) {
      // SQLite可能在只读WAL连接时建立SHM。无法证明零写的模式在驱动打开前拒绝；禁用immutable或读写回退。
      for (String suffix : List.of("-wal", "-shm", "-journal"))
        if (Files.exists(
            database.resolveSibling(database.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS))
          throw unavailable();
      byte[] header;
      try (var input = Files.newInputStream(database, LinkOption.NOFOLLOW_LINKS)) {
        header = input.readNBytes(100);
      }
      if (header.length != 100 || header[18] != 1 || header[19] != 1) throw unavailable();
      try (var c =
              DriverManager.getConnection(
                  "jdbc:sqlite:" + database.toUri().toASCIIString() + "?mode=ro");
          var statement = c.createStatement()) {
        statement.execute("PRAGMA query_only=ON");
        try (var rs = statement.executeQuery("PRAGMA query_only")) {
          if (!rs.next() || rs.getInt(1) != 1) throw unavailable();
        }
        try (var rs = statement.executeQuery("SELECT id,version FROM aegisos_schema")) {
          if (!rs.next() || rs.getInt(1) != 1) throw unavailable();
          int version = rs.getInt(2);
          if (rs.next()) throw unavailable();
          if (version >= 1 && version < 4)
            throw new SessionQueryException(State.MIGRATION_REQUIRED);
          if (version != 4) throw unavailable();
        }
        ExistingSchemaValidator.validate(c, 4);
        // Instant.toString的小数位可变，不能按TEXT或精度不足的julianday排序。
        // 连接级纯函数保留秒/纳秒精度，不执行DDL、不改存储格式。
        timestampFunction(c, "aegisos_seconds", false);
        timestampFunction(c, "aegisos_nanos", true);
        String filter = query.profile() == null ? "" : " WHERE s.profile_name=?";
        var items = new ArrayList<SessionSummary>();
        try (var q =
            c.prepareStatement(
                "SELECT s.id,s.profile_name,s.created_at,s.updated_at,"
                    + "(SELECT count(*) FROM session_messages m WHERE m.session_id=s.id),"
                    + "(SELECT count(*) FROM agent_processings p WHERE p.session_id=s.id) FROM sessions s"
                    + filter
                    + " ORDER BY aegisos_seconds(s.updated_at) DESC,aegisos_nanos(s.updated_at) DESC,s.id ASC LIMIT ? OFFSET ?")) {
          int n = 1;
          if (query.profile() != null) q.setString(n++, query.profile());
          q.setInt(n++, query.limit() + 1);
          q.setInt(n, query.offset());
          try (var rs = q.executeQuery()) {
            var guard = new DefaultSensitiveContentGuard(List.of());
            while (rs.next()) {
              guard.check(rs.getString(1));
              guard.check(rs.getString(2));
              items.add(
                  new SessionSummary(
                      rs.getString(1),
                      rs.getString(2),
                      Instant.parse(rs.getString(3)),
                      Instant.parse(rs.getString(4)),
                      rs.getLong(5),
                      rs.getLong(6)));
            }
          }
        }
        boolean more = items.size() > query.limit();
        return new Page(
            items.subList(0, Math.min(items.size(), query.limit())),
            query.limit(),
            query.offset(),
            more);
      }
    } catch (SessionQueryException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private static void timestampFunction(Connection connection, String name, boolean nanos)
      throws SQLException {
    org.sqlite.Function.create(
        connection,
        name,
        new org.sqlite.Function() {
          @Override
          protected void xFunc() throws SQLException {
            try {
              var time = Instant.parse(value_text(0));
              result(nanos ? time.getNano() : time.getEpochSecond());
            } catch (RuntimeException e) {
              error("INVALID_SESSION_TIMESTAMP");
            }
          }
        },
        1,
        org.sqlite.Function.FLAG_DETERMINISTIC);
  }

  private static SessionQueryException unavailable() {
    return new SessionQueryException(State.QUERY_UNAVAILABLE);
  }
}

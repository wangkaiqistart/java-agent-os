package com.aegisos.storage.session;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.memory.*;
import com.aegisos.storage.audit.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SessionBindingTest {
  @TempDir Path root;

  interface Action {
    void run(JpaSessionStore store, javax.sql.DataSource ds) throws Exception;
  }

  void store(Action action) throws Exception {
    var config = new AuditStorageConfiguration();
    try (var ds = config.dataSource(root.resolve("binding.db").toString(), 1000)) {
      var factory = config.entityManagerFactory(ds);
      factory.afterPropertiesSet();
      try {
        action.run(
            new JpaSessionStore(
                factory.getObject(),
                config.transactionManager(factory.getObject()),
                Clock.systemUTC()),
            ds);
      } finally {
        factory.destroy();
      }
    }
  }

  @Test
  void sameTupleIsIdempotentAndEachDimensionIsIsolated() throws Exception {
    store(
        (store, ds) -> {
          var id = new SessionIdentity("cli", "local", "p");
          var first = store.getOrCreateSession(id);
          assertThat(store.getOrCreateSession(id)).isEqualTo(first);
          var ids = new HashSet<String>();
          ids.add(first.sessionId());
          for (var other :
              List.of(
                  new SessionIdentity("http", "local", "p"),
                  new SessionIdentity("cli", "other", "p"),
                  new SessionIdentity("cli", "local", "q"),
                  new SessionIdentity("a:b", "c", "p"),
                  new SessionIdentity("a", "b:c", "p")))
            ids.add(store.getOrCreateSession(other).sessionId());
          assertThat(ids).hasSize(6);
        });
  }

  @Test
  void concurrentCreationHasOneBindingAndNoOrphan() throws Exception {
    store(
        (store, ds) -> {
          try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++)
              futures.add(
                  pool.submit(
                      () ->
                          store
                              .getOrCreateSession(new SessionIdentity("cli", "same", "p"))
                              .sessionId()));
            var ids = new HashSet<String>();
            for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
          }
          try (var c = ds.getConnection();
              var s = c.createStatement()) {
            for (String table : List.of("sessions", "session_bindings"))
              try (var rs = s.executeQuery("select count(*) from " + table)) {
                assertThat(rs.getInt(1)).isEqualTo(1);
              }
          }
        });
  }

  @Test
  void rejectedBindingRollsBackCandidateSession() throws Exception {
    store(
        (store, ds) -> {
          try (var c = ds.getConnection();
              var s = c.createStatement()) {
            s.execute(
                "CREATE TRIGGER reject_binding BEFORE INSERT ON session_bindings BEGIN SELECT RAISE(ABORT,'synthetic-rejection'); END");
          }
          assertThatThrownBy(
                  () -> store.getOrCreateSession(new SessionIdentity("cli", "local", "p")))
              .hasMessage("PERSISTENCE_UNCONFIRMED");
          try (var c = ds.getConnection();
              var s = c.createStatement();
              var rs = s.executeQuery("select count(*) from sessions")) {
            assertThat(rs.getInt(1)).isZero();
          }
        });
  }
}

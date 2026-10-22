package com.aegisos.storage.audit;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

class JpaLlmCallAuditStoreTest {
  @TempDir Path dir;
  HikariDataSource ds;
  LocalContainerEntityManagerFactoryBean factory;
  JpaLlmCallAuditStore store;
  Path db;

  void open() {
    db = dir.resolve("audit.db");
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:sqlite:" + db);
    config.setMaximumPoolSize(1);
    config.addDataSourceProperty("busy_timeout", "150");
    ds = new HikariDataSource(config);
    AuditSchemaInitializer.initialize(ds);
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(ds);
    factory.setPackagesToScan("com.aegisos.storage.audit");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.dialect",
            "org.hibernate.community.dialect.SQLiteDialect",
            "hibernate.hbm2ddl.auto",
            "none",
            "hibernate.show_sql",
            "false"));
    factory.afterPropertiesSet();
    store =
        new JpaLlmCallAuditStore(
            factory.getObject(), new JpaTransactionManager(factory.getObject()));
  }

  @AfterEach
  void close() {
    if (factory != null) {
      factory.destroy();
      factory = null;
    }
    if (ds != null) {
      ds.close();
      ds = null;
    }
  }

  AuditStart start(UUID request, int n) {
    return new AuditStart(request, n, "p", "m", Instant.now());
  }

  /** 直接检查物理列和 SQL CHECK，避免只验证实体 getter。 */
  @Test
  void v2ColumnsAndNullableStateConstraintsExist() throws Exception {
    open();
    store.begin(start(UUID.randomUUID(), 1));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      var columns = new ArrayList<String>();
      try (var r = s.executeQuery("PRAGMA table_info(llm_calls)")) {
        while (r.next()) columns.add(r.getString("name"));
      }
      assertThat(columns).contains("session_id", "success", "error_message");

      for (String update :
          List.of(
              "session_id='A'||char(0)||'hidden'",
              "success=0",
              "status='SUCCEEDED',completed_at='now',duration_ms=0,success=NULL",
              "status='FAILED',completed_at='now',duration_ms=0,error_code='INVALID_REQUEST',success=0,error_message=NULL",
              "status='FAILED',completed_at='now',duration_ms=0,error_code='INVALID_REQUEST',success=0,error_message=''")) {
        assertThatThrownBy(() -> s.executeUpdate("UPDATE llm_calls SET " + update))
            .isInstanceOf(SQLException.class);
      }
    }
  }

  @Test
  void beginIsCommittedAndCompletionIsConditionalAndDurable() throws Exception {
    open();
    var id = UUID.randomUUID();
    var h = store.begin(start(id, 1));
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var s = c.createStatement();
        var rs = s.executeQuery("select status, input_tokens from llm_calls")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("STARTED");
      assertThat(rs.getObject(2)).isNull();
    }
    var completion =
        new AuditCompletion(AuditStatus.SUCCEEDED, Instant.now(), 1, null, null, false);
    store.complete(h, completion);
    assertThatThrownBy(() -> store.complete(h, completion))
        .isInstanceOf(AuditUnavailableException.class);
    close();
    open();
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var rs = s.executeQuery("select status, input_tokens from llm_calls")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("SUCCEEDED");
      assertThat(rs.getObject(2)).isNull();
      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void duplicateAttemptsAreRejectedAndStartedIsNotReplayed() throws Exception {
    open();
    var id = UUID.randomUUID();
    store.begin(start(id, 1));
    assertThatThrownBy(() -> store.begin(start(id, 1)))
        .isInstanceOf(AuditUnavailableException.class);
    store.reject(
        new AuditRejection(
            UUID.randomUUID(), null, null, Instant.now(), ProviderErrorCode.PROVIDER_REQUIRED));
    close();
    open();
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var rs = s.executeQuery("select count(*) from llm_calls where status='STARTED'")) {
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void schemaVersionMismatchFailsClosed() throws Exception {
    open();
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.executeUpdate("update aegisos_schema set version=99");
    }
    assertThatThrownBy(() -> AuditSchemaInitializer.initialize(ds))
        .isInstanceOf(AuditUnavailableException.class);
  }

  @Test
  void sqlConstraintsRejectInvalidTerminalAndTokenValues() throws Exception {
    open();
    var h = store.begin(start(UUID.randomUUID(), 1));
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("update llm_calls set input_tokens=-1"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> s.executeUpdate("update llm_calls set status='FAILED'"))
          .isInstanceOf(SQLException.class);
    }
    assertThatThrownBy(
            () ->
                store.complete(
                    new AuditHandle(UUID.randomUUID()),
                    new AuditCompletion(
                        AuditStatus.SUCCEEDED, Instant.now(), 0, null, null, false)))
        .isInstanceOf(AuditUnavailableException.class);
  }
}

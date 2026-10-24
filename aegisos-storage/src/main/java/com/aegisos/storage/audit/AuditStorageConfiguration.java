package com.aegisos.storage.audit;

import com.aegisos.core.audit.*;
import com.aegisos.core.platform.OwnerOnlyFiles;
import com.zaxxer.hikari.*;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

@Configuration(proxyBeanMethods = false)
public class AuditStorageConfiguration {
  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(
      @Value("${aegisos.audit.path:.aegisos/aegisos.db}") String file,
      @Value("${aegisos.audit.busy-timeout-ms:1000}") int busyTimeout) {
    try {
      if (busyTimeout <= 0) throw new AuditUnavailableException();
      Path path = OwnerOnlyFiles.databasePath(file);
      Path parent = path.getParent();
      if (parent == null) throw new AuditUnavailableException();
      OwnerOnlyFiles.createDirectories(parent);
      var lock = com.aegisos.storage.runtime.RuntimeDatabaseLock.acquire(path);
      var config = new HikariConfig();
      config.setJdbcUrl("jdbc:sqlite:" + path);
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(1);
      config.setPoolName("aegisos-audit");
      config.setConnectionTimeout(2000);
      config.addDataSourceProperty("busy_timeout", Integer.toString(busyTimeout));
      config.addDataSourceProperty("foreign_keys", "true");
      config.setConnectionInitSql("PRAGMA foreign_keys=ON");
      LockedDataSource ds;
      try {
        ds = new LockedDataSource(config, lock);
      } catch (RuntimeException e) {
        lock.close();
        throw e;
      }
      try {
        AuditSchemaInitializer.initialize(ds);
        return ds;
      } catch (RuntimeException e) {
        ds.close();
        throw e;
      }
    } catch (com.aegisos.core.agent.AgentException e) {
      throw e;
    } catch (AuditMigrationRequiredException e) {
      throw e;
    } catch (java.io.IOException | RuntimeException e) {
      throw new AuditUnavailableException();
    }
  }

  /** 数据源生命周期拥有运行锁；关闭连接池后才允许其他进程恢复此库。 */
  private static final class LockedDataSource extends HikariDataSource {
    private final com.aegisos.storage.runtime.RuntimeDatabaseLock lock;

    LockedDataSource(HikariConfig config, com.aegisos.storage.runtime.RuntimeDatabaseLock lock) {
      super(config);
      this.lock = lock;
    }

    /** 每次借出连接均验证外键，包括池中更换后的连接；错误仅返回固定说明。 */
    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
      var connection = super.getConnection();
      try (var statement = connection.createStatement();
          var result = statement.executeQuery("PRAGMA foreign_keys")) {
        if (!result.next() || result.getInt(1) != 1) {
          throw new java.sql.SQLException("FOREIGN_KEYS_UNAVAILABLE");
        }
        return connection;
      } catch (java.sql.SQLException | RuntimeException e) {
        connection.close();
        throw new java.sql.SQLException("FOREIGN_KEYS_UNAVAILABLE");
      }
    }

    @Override
    public void close() {
      try {
        super.close();
      } finally {
        if (lock != null) lock.close();
      }
    }
  }

  @Bean
  public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
    var f = new LocalContainerEntityManagerFactoryBean();
    f.setDataSource(ds);
    f.setPackagesToScan("com.aegisos.storage.audit");
    f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    f.setJpaPropertyMap(
        Map.of(
            "hibernate.dialect",
            "org.hibernate.community.dialect.SQLiteDialect",
            "hibernate.hbm2ddl.auto",
            "none",
            "hibernate.show_sql",
            "false",
            "hibernate.format_sql",
            "false"));
    return f;
  }

  @Bean
  public JpaTransactionManager transactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }

  @Bean
  public LlmCallAuditStore llmCallAuditStore(EntityManagerFactory emf, JpaTransactionManager tm) {
    return new JpaLlmCallAuditStore(emf, tm);
  }
}

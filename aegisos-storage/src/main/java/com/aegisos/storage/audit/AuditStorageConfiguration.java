package com.aegisos.storage.audit;

import com.aegisos.core.audit.*;
import com.zaxxer.hikari.*;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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
      if (busyTimeout <= 0
          || file == null
          || file.isBlank()
          || file.contains(":")
          || file.contains("?")) throw new AuditUnavailableException();
      Path path = Path.of(file).toAbsolutePath().normalize();
      Path parent = path.getParent();
      if (parent == null) throw new AuditUnavailableException();
      if (!Files.exists(parent)) {
        try {
          Files.createDirectories(
              parent,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
          Files.createDirectories(parent);
        }
      }
      var config = new HikariConfig();
      config.setJdbcUrl("jdbc:sqlite:" + path);
      config.setMaximumPoolSize(1);
      config.setMinimumIdle(1);
      config.setPoolName("aegisos-audit");
      config.setConnectionTimeout(2000);
      config.addDataSourceProperty("busy_timeout", Integer.toString(busyTimeout));
      var ds = new HikariDataSource(config);
      try {
        AuditSchemaInitializer.initialize(ds);
        return ds;
      } catch (RuntimeException e) {
        ds.close();
        throw e;
      }
    } catch (AuditMigrationRequiredException e) {
      throw e;
    } catch (java.io.IOException | RuntimeException e) {
      throw new AuditUnavailableException();
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

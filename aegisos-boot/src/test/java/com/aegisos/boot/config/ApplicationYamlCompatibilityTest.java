package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** 比较配置资源实际绑定值，防止旧 properties 覆盖 YAML 或重新开启敏感日志。 */
class ApplicationYamlCompatibilityTest {
  @Test
  void yamlKeepsBaselineAndNoPropertiesOverride() throws Exception {
    var loader = new YamlPropertySourceLoader();
    var main = loader.load("main", new ClassPathResource("application.yml")).getFirst();
    assertThat(main.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
    assertThat(main.getProperty("spring.ai.chat.observations.log-prompt")).isEqualTo(false);
    assertThat(main.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
    assertThat(
            loader
                .load("dev", new ClassPathResource("application-dev.yml"))
                .getFirst()
                .getProperty("springdoc.api-docs.enabled"))
        .isEqualTo(true);
    assertThat(
            loader
                .load("metrics", new ClassPathResource("application-metrics.yml"))
                .getFirst()
                .getProperty("management.server.address"))
        .isEqualTo("127.0.0.1");
    assertThat(
            loader
                .load("test", new ClassPathResource("application-provider-test.yml"))
                .getFirst()
                .getProperty("spring.jpa.hibernate.ddl-auto"))
        .isEqualTo("none");
    for (String name :
        java.util.List.of(
            "application", "application-dev", "application-metrics", "application-provider-test"))
      assertThat(new ClassPathResource(name + ".properties").exists()).isFalse();
  }
}

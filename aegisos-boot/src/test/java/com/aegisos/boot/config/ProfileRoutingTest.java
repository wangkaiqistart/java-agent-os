package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** 双 loopback Provider + 真实 SQLite 验证精确路由、零执行以及统一拒绝审计。 */
class ProfileRoutingTest {
  @TempDir Path dir;

  @Test
  void explicitRoutingSameBeanAndRejectionsStayAudited() throws Exception {

    try (var a =
            new BootProviderFixture(
                (e, n) -> {
                  BootProviderFixture.reply(e, 200, BootProviderFixture.text());
                });
        var b =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()));
        var context = new AnnotationConfigApplicationContext()) {
      CourseAlignmentFixtures.agent(
          dir,
          "one",
          "name: a\nidentity: {prompt: PRIVATE_NOT_SENT}\nprovider: {name: p, model: m, temperature: 0.3}\ntools: [lookup]\n");
      CourseAlignmentFixtures.agent(dir, "two", "name: b\nprovider: {name: q, model: n}\n");
      var values = new HashMap<String, Object>();
      values.put("aegisos.audit.path", dir.resolve("audit.db").toString());
      values.put("aegisos.workspace-root", dir.toString());
      int i = 0;
      for (var stub : List.of(a, b)) {
        String prefix = "aegisos.providers[" + (i++) + "].";
        values.put(prefix + "name", stub == a ? "p" : "q");
        values.put(prefix + "models[0]", stub == a ? "m" : "n");
        values.put(prefix + "adapter", "openai-compatible");
        values.put(prefix + "base-url", stub.uri().toString());
        values.put(prefix + "credential-ref", "env:SYNTHETIC_KEY");
      }
      context.getEnvironment().setActiveProfiles("test");
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
      context.registerBean(
          EnvironmentCredentialResolver.class,
          () -> new EnvironmentCredentialResolver(n -> UUID.randomUUID().toString()));
      context.register(ProviderConfiguration.class, AuditStorageConfiguration.class);
      context.refresh();
      var service = context.getBean(ProfileProviderService.class);
      assertThat(service).isSameAs(context.getBean(ProviderService.class));
      service.chatProfile("SessionA", "a", ProfilePrompt.text("hello"));
      service.chatProfile("SessionB", "b", ProfilePrompt.text("hello"));
      assertThat(a.calls).hasValue(1);
      assertThat(b.calls).hasValue(1);
      // BootProviderFixture 已捕获正文，避免读取同一流两次。
      var json = JsonMapper.builder().build().readTree(a.bodies.getFirst());
      assertThat(json.path("model").asText()).isEqualTo("m");
      assertThat(json.path("temperature").asDouble()).isEqualTo(0.3);
      assertThat(a.bodies.getFirst()).doesNotContain("SessionA", "PRIVATE_NOT_SENT");
      for (String name : Arrays.asList(null, "missing"))
        assertThatThrownBy(() -> service.chatProfile("SessionA", name, ProfilePrompt.text("x")))
            .isInstanceOfSatisfying(
                ProviderException.class,
                e -> {
                  assertThat(e.failure().requestId()).isNotNull();
                  assertThat(e.failure().attemptCount()).isZero();
                  assertThat(e.getMessage())
                      .isEqualTo(name == null ? "PROFILE_REQUIRED" : "UNKNOWN_PROFILE");
                });
      assertThatThrownBy(
              () ->
                  service.chatProfile(
                      "SessionA",
                      "a",
                      new ProfilePrompt(
                          ProfilePrompt.text("x").messages(),
                          List.of(new ToolSchema("forbidden", null, "{}")),
                          null)))
          .hasMessage("INVALID_REQUEST");
      assertThat(a.calls).hasValue(1);
      assertThat(b.calls).hasValue(1);
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("audit.db"));
          var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT count(*) FROM llm_calls WHERE session_id='SessionA' AND status='REJECTED' AND attempt_no=0")) {
        assertThat(r.getInt(1)).isEqualTo(3);
      }
    }
  }
}

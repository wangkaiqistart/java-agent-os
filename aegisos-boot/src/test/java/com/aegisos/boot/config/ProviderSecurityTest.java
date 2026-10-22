package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.*;
import com.aegisos.provider.config.*;
import com.aegisos.provider.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.*;

@ExtendWith(OutputCaptureExtension.class)
class ProviderSecurityTest {
  @TempDir Path dir;

  static String secret() {
    return "US1CANARY_" + UUID.randomUUID().toString().replace("-", "");
  }

  /** 本轮新增边界：YAML/环境错误、未知配置和非法会话均不得保留秘密片段。 */
  @Test
  void profileParserEnvironmentAndRejectionsRedactSentinels(CapturedOutput logs) throws Exception {
    String sentinel = secret();
    for (String yaml :
        List.of(
            "name: [" + sentinel,
            "name: a\nprovider: {name: p, model: '${SECRET}'}\n",
            "name: a\nprovider: {name: p, model: m}\nchannels: [{name: cli, config: {password: '"
                + sentinel
                + "'}}]\n")) {
      Path root = Files.createTempDirectory(dir, "profile-");
      CourseAlignmentFixtures.agent(root, "one", yaml);
      var report = new ProfileLoader(root, ProfileLoaderTest.providers(), name -> sentinel).load();
      assertThat(report.profiles()).isEmpty();
      assertThat((report.toString() + report.diagnostics()).contains(sentinel)).isFalse();
    }
    assertThatThrownBy(
            () ->
                new ProfileEnvironmentResolver(
                        n -> {
                          throw new RuntimeException(sentinel);
                        })
                    .resolve("${X}"))
        .hasMessage("ENV_MISSING")
        .hasNoCause();
    Path db = dir.resolve("session-safe.db");
    try (var stub =
            new BootProviderFixture(
                (e, n) ->
                    BootProviderFixture.reply(
                        e, 401, "{\"error\":{\"message\":\"" + sentinel + "\"}}"));
        var context = stub.productionContext(db)) {
      var direct = context.getBean(ProviderService.class);
      var profile = context.getBean(ProfileProviderService.class);
      assertSafe(
          () -> direct.chat("invalid/" + sentinel, ModelRequest.text("p", "m", "x")),
          List.of(sentinel));
      assertSafe(
          () -> profile.chatProfile("safe", sentinel, ProfilePrompt.text(sentinel)),
          List.of(sentinel));
      assertSafe(
          () -> direct.chat("safe", ModelRequest.text("p", "m", sentinel)), List.of(sentinel));
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
          var q = c.createStatement();
          var r = q.executeQuery("SELECT * FROM llm_calls")) {
        while (r.next())
          for (int j = 1; j <= r.getMetaData().getColumnCount(); j++) {
            String value = r.getString(j);
            assertThat(value != null && value.contains(sentinel)).isFalse();
          }
      }
    }
    assertThat(logs.getAll().contains(sentinel)).isFalse();
  }

  @Test
  void bodiesCredentialsUrlsAndCausesNeverReachAuditLogsOrPublicErrors(CapturedOutput logs)
      throws Exception {
    String key = secret(),
        input = secret(),
        output = secret(),
        arguments = secret(),
        schema = secret(),
        upstream = secret(),
        exception = secret();
    var secrets = List.of(key, input, output, arguments, schema, upstream, exception);
    Path db = dir.resolve("safe.db");
    try (var stub =
            new BootProviderFixture(
                (e, n) -> {
                  if (n == 1)
                    BootProviderFixture.reply(
                        e,
                        200,
                        BootProviderFixture.text()
                            .replace(
                                "\"content\":\"ok\"",
                                "\"content\":\""
                                    + output
                                    + "\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{\\\"value\\\":\\\""
                                    + arguments
                                    + "\\\"}\"}}]"));
                  else if (n == 2)
                    BootProviderFixture.reply(
                        e, 401, "{\"error\":{\"message\":\"" + upstream + "\"}}");
                  else if (n == 3) BootProviderFixture.reply(e, 200, "{broken" + upstream);
                  else if (n == 4) {
                    Thread.sleep(500);
                    BootProviderFixture.reply(e, 200, BootProviderFixture.text());
                  } else BootProviderFixture.reply(e, 200, BootProviderFixture.text());
                });
        var c = stub.manualContext(db)) {
      var definition =
          new ProviderDefinition(
              "p",
              "openai-compatible",
              Set.of("m"),
              stub.uri(),
              "env:KEY",
              0,
              Duration.ofSeconds(1),
              Duration.ofMillis(150));
      try (var adapter = new OpenAiClientFactory().create(definition, key)) {
        var registration = new ProviderRegistration(definition, adapter, null);
        var audit = c.getBean(LlmCallAuditStore.class);
        var service = new DefaultProviderService(List.of(registration), audit, () -> {});
        var request =
            new ModelRequest(
                "p",
                "m",
                ModelRequest.text("p", "m", input).messages(),
                List.of(
                    new ToolSchema(
                        "write_file", schema, "{\"type\":\"object\",\"properties\":{}}")),
                null);
        var response = service.chat(request);
        assertThat(response.content().equals(output)).isTrue();
        assertThat(response.toString().contains(output)).isFalse();
        for (int i = 0; i < 3; i++) assertSafe(() -> service.chat(request), secrets);
        assertSafe(
            () -> service.chat(ModelRequest.text("https://example.org/?key=" + key, "m", input)),
            secrets);
        assertSafe(() -> service.chat(ModelRequest.text("p", "m\n" + input, input)), secrets);
        // 格式合法但未注册的标识也可能是误传凭证，不能回显或持久化。
        assertSafe(() -> service.chat(ModelRequest.text(key, "m", input)), secrets);
        assertSafe(() -> service.chat(ModelRequest.text("p", key, input)), secrets);
        var broken =
            new LlmCallAuditStore() {
              public AuditHandle begin(AuditStart s) {
                return audit.begin(s);
              }

              public void complete(AuditHandle h, AuditCompletion end) {
                throw new RuntimeException("https://invalid/?key=" + exception);
              }

              public void reject(AuditRejection r) {
                throw new RuntimeException(exception);
              }
            };
        var fault = new DefaultProviderService(List.of(registration), broken, () -> {});
        assertSafe(() -> fault.chat(request), secrets);
        assertSafe(() -> fault.chat(ModelRequest.text("p", null, input)), secrets);
        assertThat(stub.calls).hasValue(5);
        assertThat(stub.auth.stream().allMatch(v -> v.equals("Bearer " + key))).isTrue();
      }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT * FROM llm_calls")) {
        int count = 0;
        while (rows.next()) {
          count++;
          for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
            String cell = rows.getString(i);
            if (cell != null) assertThat(secrets.stream().noneMatch(cell::contains)).isTrue();
          }
        }
        assertThat(count).isEqualTo(9);
      }
    }
    String all = logs.getAll();
    assertThat(secrets.stream().noneMatch(all::contains)).isTrue();
    String bytes = new String(Files.readAllBytes(db), java.nio.charset.StandardCharsets.ISO_8859_1);
    assertThat(secrets.stream().noneMatch(bytes::contains)).isTrue();
  }

  @Test
  void invalidSecretBearingConfigurationCannotLeakOrTriggerProbes(CapturedOutput logs)
      throws Exception {
    String sentinel = secret();
    ProviderConfigurationTest.runner()
        .withPropertyValues(
            "aegisos.providers[0].name=p",
            "aegisos.providers[0].models[0]=m",
            "aegisos.providers[0].adapter=openai-compatible",
            "aegisos.providers[0].credential-ref=" + sentinel,
            "aegisos.providers[0].base-url=https://user:"
                + sentinel
                + "@example.org/?key="
                + sentinel)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(ProviderRegistry.class).registrations())
                  .allMatch(r -> !r.available());
            });
    assertThat(logs.getAll().contains(sentinel)).isFalse();
  }

  private static void assertSafe(Runnable action, List<String> secrets) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ProviderException.class,
            e -> {
              var text = new java.io.StringWriter();
              e.printStackTrace(new java.io.PrintWriter(text));
              String printable = e.getMessage() + e + text + e.failure();
              assertThat(secrets.stream().noneMatch(printable::contains)).isTrue();
              assertThat(e.getCause()).isNull();
            });
  }
}

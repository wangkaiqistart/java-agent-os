package com.aegisos.boot.provider;

import com.aegisos.boot.config.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.TransportSendObserver;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 冒烟专用的最小生产装配：临时工作区与 SQLite，不启动 Web，不替换真实适配器。 */
public final class ProviderSmokeFixture implements AutoCloseable {
  public static final String PROVIDER = "deepseek-main",
      MODEL = "deepseek-flash",
      PROFILE = "course-smoke",
      SESSION = "pdf16-smoke-001";

  public record Configuration(
      Map<String, Object> properties, String profileName, String sessionId) {
    public Configuration {
      properties = Map.copyOf(properties);
    }

    @Override
    public String toString() {
      return "SmokeConfiguration[redacted]";
    }
  }

  public static final class NotRun extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotRun() {
      super("NOT_RUN:CONDITIONS_MISSING", null, false, false);
    }
  }

  /** 只检查条件，不组装连接、不发探测；环境值不写回 Configuration 或异常。 */
  public static Configuration preflight(boolean enabled, Map<String, String> environment) {
    try {
      if (!enabled) throw new NotRun();
      String file = environment.get("AEGISOS_SMOKE_CONFIG");
      String key = environment.get("DEEPSEEK_API_KEY");
      if (file == null || file.isBlank() || key == null || key.isBlank()) throw new NotRun();
      var root = new SafeProfileYamlReader().read(Path.of(file));
      keys(root, Set.of("aegisos", "smoke"));
      var aegis = map(root.get("aegisos"));
      keys(aegis, Set.of("providers"));
      if (!(aegis.get("providers") instanceof List<?> providers) || providers.size() != 1)
        throw new NotRun();
      var p = map(providers.getFirst());
      keys(
          p,
          Set.of(
              "name",
              "adapter",
              "models",
              "base-url",
              "credential-ref",
              "max-retries",
              "connect-timeout",
              "read-timeout"));
      if (!PROVIDER.equals(p.get("name"))
          || !"openai-compatible".equals(p.get("adapter"))
          || !List.of(MODEL).equals(p.get("models"))
          || !"https://api.deepseek.com".equals(p.get("base-url"))
          || !"env:DEEPSEEK_API_KEY".equals(p.get("credential-ref"))
          || !(p.get("max-retries") instanceof Number n)
          || n.doubleValue() != 0) throw new NotRun();
      for (String timeout : List.of("connect-timeout", "read-timeout")) {
        var d =
            org.springframework.boot.convert.DurationStyle.detectAndParse((String) p.get(timeout));
        if (d.isNegative() || d.isZero() || d.compareTo(Duration.ofSeconds(60)) > 0)
          throw new NotRun();
      }
      var smoke = map(root.get("smoke"));
      keys(smoke, Set.of("profile-name", "session-id"));
      // 固定非敏感验收身份，避免把误传秘密即使格式合法也保存进证据。
      if (!PROFILE.equals(smoke.get("profile-name")) || !SESSION.equals(smoke.get("session-id")))
        throw new NotRun();
      var values = new HashMap<String, Object>();
      for (var e : p.entrySet()) {
        String prefix = "aegisos.providers[0].";
        if (e.getKey().equals("models")) values.put(prefix + "models[0]", MODEL);
        else values.put(prefix + e.getKey(), e.getValue());
      }
      return new Configuration(values, PROFILE, SESSION);
    } catch (RuntimeException e) {
      throw new NotRun();
    }
  }

  private static Map<?, ?> map(Object o) {
    if (!(o instanceof Map<?, ?> m)) throw new NotRun();
    return m;
  }

  private static void keys(Map<?, ?> map, Set<String> allowed) {
    if (!allowed.containsAll(map.keySet())) throw new NotRun();
  }

  private final AnnotationConfigApplicationContext context;
  private final Path database;
  public final AtomicInteger sends = new AtomicInteger();

  public ProviderSmokeFixture(Configuration config) {
    context = new AnnotationConfigApplicationContext();
    try {
      Path root = Files.createTempDirectory("aegisos-provider-smoke-");
      database = root.resolve("audit.db");
      Path profile = root.resolve(".aegisos/agents/course-smoke/profile.yml");
      Files.createDirectories(profile.getParent());
      try (var input =
          ProviderSmokeFixture.class.getResourceAsStream(
              "/provider-smoke/agents/course-smoke/profile.yml")) {
        if (input == null) throw new NotRun();
        Files.copy(input, profile);
      }
      var properties = new HashMap<>(config.properties());
      properties.put("aegisos.audit.path", database.toString());
      properties.put("aegisos.workspace-root", root.toString());
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("smoke", properties));
      context.registerBean(TransportSendObserver.class, () -> () -> sends.incrementAndGet());
      context.register(
          ProviderConfiguration.class, AuditStorageConfiguration.class, ProfileConfiguration.class);
      context.refresh();
      var p = context.getBean(ProfileRegistry.class).get(PROFILE);
      if (p == null || !p.tools().isEmpty() || p.provider().temperature() != null)
        throw new NotRun();
    } catch (Exception e) {
      context.close();
      throw new NotRun();
    }
  }

  public ModelResponse call(Configuration config) {
    return context
        .getBean(ProfileProviderService.class)
        .chatProfile(config.sessionId(), config.profileName(), ProfilePrompt.text("请只回复 OK。"));
  }

  /** 独立 JDBC 连接验证全量新库恰好一行；close 后再次调用即验证真实重开。 */
  public boolean auditMatches(UUID request) {
    if (request == null) return false;
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + database);
        var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT session_id,request_id,attempt_no,status,success,error_code,error_message FROM llm_calls")) {
      return r.next()
          && SESSION.equals(r.getString(1))
          && request.toString().equals(r.getString(2))
          && r.getLong(3) == 1
          && "SUCCEEDED".equals(r.getString(4))
          && r.getObject(5) != null
          && r.getBoolean(5)
          && r.getString(6) == null
          && r.getString(7) == null
          && !r.next();
    } catch (SQLException e) {
      return false;
    }
  }

  @Override
  public void close() {
    context.close();
  }
}

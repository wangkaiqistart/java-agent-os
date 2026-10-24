package com.aegisos.boot.react;

import com.aegisos.boot.config.*;
import com.aegisos.cli.InitCommand;
import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.MemoryService;
import com.aegisos.core.profile.ProfileRegistry;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import com.aegisos.provider.adapter.TransportSendObserver;
import com.aegisos.provider.service.DefaultProviderService;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import java.net.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** 默认不发现的真实验收。只有显式 profile + 专用 YAML + 受控环境齐备才可外呼。 */
@Tag("real-react")
class ReactWeatherSmokeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void weatherConversation() throws Exception {
    var evidence = emptyEvidence();
    RealReactSmokeConfiguration config = null;
    RealReactSmokeBudget budget = null;
    try {
      if (!Boolean.getBoolean("aegisos.react-smoke.enabled")) throw missing();
      String location = System.getenv("AEGISOS_REACT_SMOKE_CONFIG");
      if (location == null || location.isBlank()) throw missing();
      config = RealReactSmokeConfiguration.read(Path.of(location));
      evidence.put("runId", config.runId);
      evidence.put("configFingerprint", config.fingerprint);
      System.setProperty("aegisos.react-smoke.run-id", config.runId);
      String credential = System.getenv("DEEPSEEK_API_KEY");
      if (credential == null || credential.isBlank()) throw missing();
      try (var directory = Files.newDirectoryStream(config.parent)) {
        if (!(directory instanceof SecureDirectoryStream<?>)) throw missing();
      }
      if (Files.exists(config.parent.resolve(config.runId), LinkOption.NOFOLLOW_LINKS))
        throw missing();
      var guard = new DefaultSensitiveContentGuard(List.of(credential));
      for (String message : config.messages) guard.check(message);
      Path workspace = Files.createDirectory(config.parent.resolve(config.runId));
      configure(workspace, config);
      budget = new RealReactSmokeBudget(config.iterations, config.modelLimit, config.getLimit);
      var observations = new ArrayList<WeatherFactAssertions.Observation>();
      var pending = new AtomicReference<ProfilePrompt>();
      var sent = new AtomicReference<ProfilePrompt>();
      var runtime = open(workspace, budget, pending, sent);
      var results = new ArrayList<ProcessingResult>();
      try (runtime) {
        MemoryService memory = runtime.getBean(MemoryService.class);
        var provider = runtime.getBean(DefaultProviderService.class);
        ToolExecutor executor = runtime.getBean(ToolExecutor.class);
        final var approved = config;
        final var quota = budget;
        ProfileProviderService observedProvider =
            new ProfileProviderService() {
              public ModelResponse chatProfile(
                  String session, String profile, ProfilePrompt prompt) {
                throw new IllegalStateException("EXPLICIT_INVOCATION_REQUIRED");
              }

              public ModelResponse chatProfile(
                  String session,
                  String profile,
                  ProfilePrompt prompt,
                  ProviderInvocation invocation) {
                quota.model(invocation.processId());
                pending.set(prompt);
                sent.set(null);
                int before = quota.physical;
                ModelResponse response = provider.chatProfile(session, profile, prompt, invocation);
                // 只在真实传输回调已发生且恰有一次发送后确认该 Prompt 已进入本次外呼。
                if (sent.get() != prompt || quota.physical != before + 1)
                  throw new IllegalStateException("MODEL_SEND_MISMATCH");
                if (response.toolCalls().isEmpty()) {
                  guard.check(response.content());
                  var ids = new ArrayList<String>();
                  for (var message : sent.get().messages())
                    if (message.role() == ModelMessage.Role.TOOL) {
                      var outer = JSON.readTree(message.content());
                      if (!outer.path("success").asBoolean()
                          || !"http_get".equals(outer.path("name").asText())) continue;
                      String id = outer.path("auditId").asText();
                      String body =
                          JSON.readTree(outer.path("data").asText()).path("body").asText();
                      var captured =
                          observations.stream()
                              .filter(o -> o.audit().equals(id))
                              .findFirst()
                              .orElseThrow(
                                  () -> new IllegalStateException("OBSERVATION_NOT_CAPTURED"));
                      if (!captured.body().equals(body))
                        throw new IllegalStateException("OBSERVATION_CHANGED");
                      ids.add(id);
                    }
                  var latest = WeatherFactAssertions.latest(session, observations, ids);
                  var fact =
                      WeatherFactAssertions.weather(
                          latest.body(),
                          approved.latitude,
                          approved.longitude,
                          approved.zone,
                          latest.received());
                  WeatherFactAssertions.answer(fact, response.content());
                }
                return response;
              }
            };
        ToolExecutor observedExecutor =
            (context, call) -> {
              if (!"http_get".equals(call.name()))
                throw new IllegalStateException("UNEXPECTED_TOOL");
              validateTarget(call.argumentsJson(), approved);
              boolean reused =
                  memory.findCommittedObservation(context.handle(), call.id()).isPresent();
              if (!reused) quota.get(); // 新执行在 Sandbox/DNS/HTTP 之前扣额度，可靠复用不计作新GET。
              ToolObservation observation = executor.execute(context, call);
              if (!observation.success()) throw new IllegalStateException("WEATHER_TOOL_FAILED");
              String body = JSON.readTree(observation.data()).path("body").asText();
              Instant received = Instant.now();
              WeatherFactAssertions.weather(
                  body, approved.latitude, approved.longitude, approved.zone, received);
              if (!reused)
                observations.add(
                    new WeatherFactAssertions.Observation(
                        context.handle().sessionId(),
                        context.handle().processId().toString(),
                        observation.auditId().toString(),
                        body,
                        received));
              return observation;
            };
        var loop =
            new ReActLoop(
                observedProvider,
                memory,
                runtime.getBean(PromptBuilder.class),
                observedExecutor,
                runtime.getBean(ContextBudget.class),
                guard);
        var agents =
            new AgentService(
                runtime.getBean(ProfileRegistry.class), memory, loop, guard, UUID.randomUUID());
        String session = agents.createSession("weather").sessionId();
        evidence.put("sessionId", session);
        for (String message : config.messages) {
          ProcessingResult result = agents.process(new AgentRequest(session, "weather", message));
          results.add(result);
          evidence.put(
              "processIds",
              results.stream()
                  .map(r -> r.processId() == null ? "" : r.processId().toString())
                  .toList());
          evidence.put("statuses", results.stream().map(r -> r.status().name()).toList());
          evidence.put(
              "acceptedIterations",
              results.stream().map(ProcessingResult::iterationCount).toList());
          if (result.status() != ProcessingStatus.COMPLETED || !result.persistenceConfirmed())
            throw new IllegalStateException("PROCESSING_NOT_COMPLETED");
        }
        if (quota.gets < 1 || observations.size() != quota.gets)
          throw new IllegalStateException("WEATHER_NOT_EXECUTED");
        for (String field :
            List.of("weatherValid", "observationForwarded", "factMatched", "advicePresent"))
          evidence.put(field, true);
      }
      // 关闭整个 Spring/数据库运行锁后，以新连接核验真实持久数据，不复用一级缓存。
      try (var connection =
              DriverManager.getConnection(
                  "jdbc:sqlite:" + workspace.resolve(".aegisos/aegisos.db"));
          var statement = connection.createStatement()) {
        try (var rows =
            statement.executeQuery(
                "SELECT count(*) FROM agent_processings WHERE status='COMPLETED'")) {
          if (!rows.next() || rows.getInt(1) != 2)
            throw new IllegalStateException("AUDIT_REOPEN_FAILED");
        }
        try (var rows =
            statement.executeQuery(
                "SELECT count(*) FROM llm_calls l JOIN llm_request_links k ON k.request_id=l.request_id JOIN agent_processings p ON p.id=k.process_id WHERE l.session_id=p.session_id AND l.status='SUCCEEDED' AND l.attempt_no=1 AND p.status='COMPLETED'")) {
          if (!rows.next() || rows.getInt(1) != budget.physical)
            throw new IllegalStateException("AUDIT_ASSOCIATION_FAILED");
        }
        try (var rows =
            statement.executeQuery(
                "SELECT count(*) FROM tool_invocations WHERE status='SUCCEEDED' AND tool_name='http_get' AND success=1")) {
          if (!rows.next() || rows.getInt(1) != budget.gets)
            throw new IllegalStateException("TOOL_AUDIT_FAILED");
        }
      }
      evidence.put("auditReopened", true);
      evidence.put("associationValid", true);
      evidence.put("status", "PASS");
      evidence.put("errorCode", null);
    } catch (MissingConditions e) {
      evidence.put("status", "NOT_RUN");
      evidence.put("errorCode", "CONDITIONS_MISSING");
      throw new AssertionError("REACT_SMOKE_NOT_RUN");
    } catch (Exception | AssertionError e) {
      evidence.put("status", "FAIL");
      evidence.put("errorCode", "REACT_SMOKE_FAILED");
      throw new AssertionError("REACT_SMOKE_FAILED");
    } finally {
      if (budget != null) {
        evidence.put("logicalRequests", budget.logical);
        evidence.put("physicalSends", budget.physical);
        evidence.put("toolExecutions", budget.gets);
        evidence.put("weatherGets", budget.gets);
      }
      evidence.put("completedAt", Instant.now().toString());
      Path output = Path.of("target/react-smoke-result.json");
      Files.createDirectories(output.getParent());
      Files.writeString(output, JSON.writeValueAsString(evidence));
    }
  }

  private static AnnotationConfigApplicationContext open(
      Path workspace,
      RealReactSmokeBudget budget,
      AtomicReference<ProfilePrompt> pending,
      AtomicReference<ProfilePrompt> sent) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "approved-smoke", new CliWorkspaceConfiguration().load(workspace)));
    context.registerBean(
        TransportSendObserver.class,
        () ->
            () -> {
              budget.physical();
              sent.set(pending.get());
            });
    context.register(
        ProviderConfiguration.class,
        AuditStorageConfiguration.class,
        AgentRuntimeConfiguration.class);
    try {
      context.refresh();
      return context;
    } catch (RuntimeException | Error e) {
      context.close();
      throw e;
    }
  }

  static void configure(Path workspace, RealReactSmokeConfiguration config) throws Exception {
    new InitCommand().run(workspace);
    var global = new SafeProfileYamlReader().read(workspace.resolve(".aegisos/config.yml"));
    var aegisos = RealReactSmokeConfiguration.map(global, "aegisos");
    var declared = RealReactSmokeConfiguration.map(config.tree, "provider");
    var provider = new LinkedHashMap<String, Object>();
    provider.put("name", declared.get("name"));
    provider.put("adapter", "openai-compatible");
    provider.put("models", List.of(declared.get("model")));
    provider.put("base-url", declared.get("base-url"));
    provider.put("credential-ref", declared.get("credential-ref"));
    provider.put("thinking-mode", declared.get("thinking-mode"));
    provider.put("max-retries", 0);
    aegisos.put("providers", List.of(provider));
    var http =
        RealReactSmokeConfiguration.map(
            RealReactSmokeConfiguration.map(aegisos, "sandbox"), "http-get");
    http.put(
        "targets",
        List.of(
            RealReactSmokeConfiguration.map(
                RealReactSmokeConfiguration.map(config.tree, "weather"), "target")));
    aegisos.put("sandbox", Map.of("http-get", http));
    var yaml = new org.yaml.snakeyaml.Yaml();
    Files.writeString(
        workspace.resolve(".aegisos/config.yml"), yaml.dump(Map.of("aegisos", aegisos)));
    Path profile = workspace.resolve(".aegisos/agents/weather/profile.yml");
    var p = new SafeProfileYamlReader().read(profile);
    p.put("settings", Map.of("max_iterations", config.iterations, "max_history_turns", 20));
    Files.writeString(profile, yaml.dump(p));
    String query =
        "https://api.open-meteo.com/v1/forecast?latitude="
            + config.latitude.toPlainString()
            + "&longitude="
            + config.longitude.toPlainString()
            + "&current=temperature_2m,weather_code,wind_speed_10m&timezone="
            + URLEncoder.encode(config.zone, java.nio.charset.StandardCharsets.UTF_8);
    String instruction =
        "仅使用 http_get 查询以下批准的固定天气地址，不得改城市、字段、来源或使用其他工具：\n"
            + query
            + "\n每次最终答复必须包含唯一的气温字段和建议字段，格式：气温：数值 °C；建议：具体建议。气温按最新工具观察四舍五入保留一位小数；没有有效观察时不得编造。";
    Files.writeString(workspace.resolve(".aegisos/agents/weather/AGENT.md"), instruction);
  }

  static void validateTarget(String arguments, RealReactSmokeConfiguration config) {
    var node = JSON.readTree(arguments);
    if (!node.isObject() || node.size() != 1 || !node.path("url").isTextual())
      throw new IllegalArgumentException("INVALID_WEATHER_TARGET");
    URI uri = URI.create(node.get("url").asText());
    if (!"https".equals(uri.getScheme())
        || !"api.open-meteo.com".equals(uri.getHost())
        || !(uri.getPort() == -1 || uri.getPort() == 443)
        || !"/v1/forecast".equals(uri.getRawPath())
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || uri.getRawQuery() == null) throw new IllegalArgumentException("INVALID_WEATHER_TARGET");
    var query = new HashMap<String, String>();
    for (String pair : uri.getRawQuery().split("&", -1)) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) throw new IllegalArgumentException("INVALID_WEATHER_TARGET");
      String key = URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8),
          value = URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
      if (query.putIfAbsent(key, value) != null)
        throw new IllegalArgumentException("INVALID_WEATHER_TARGET");
    }
    if (!query.keySet().equals(Set.of("latitude", "longitude", "current", "timezone"))
        || new java.math.BigDecimal(query.get("latitude")).compareTo(config.latitude) != 0
        || new java.math.BigDecimal(query.get("longitude")).compareTo(config.longitude) != 0
        || !"temperature_2m,weather_code,wind_speed_10m".equals(query.get("current"))
        || !config.zone.equals(query.get("timezone")))
      throw new IllegalArgumentException("INVALID_WEATHER_TARGET");
  }

  static Map<String, Object> emptyEvidence() {
    var e = new LinkedHashMap<String, Object>();
    e.put("runId", "unconfigured");
    e.put("startedAt", Instant.now().toString());
    e.put("completedAt", null);
    e.put("configFingerprint", "");
    e.put("sessionId", null);
    e.put("processIds", List.of());
    e.put("statuses", List.of());
    e.put("acceptedIterations", List.of());
    for (String key : List.of("logicalRequests", "physicalSends", "toolExecutions", "weatherGets"))
      e.put(key, 0);
    for (String key :
        List.of(
            "weatherValid",
            "observationForwarded",
            "factMatched",
            "advicePresent",
            "auditReopened",
            "associationValid")) e.put(key, false);
    e.put("status", "NOT_RUN");
    e.put("errorCode", "CONDITIONS_MISSING");
    return e;
  }

  private static final class MissingConditions extends RuntimeException {}

  private static MissingConditions missing() {
    return new MissingConditions();
  }
}

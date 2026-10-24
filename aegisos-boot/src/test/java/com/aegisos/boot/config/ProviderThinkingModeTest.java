package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.config.ProviderRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 仅使用协议替身；核验配置经过生产装配后的两次真实HTTP请求，不接触厂商或环境凭据。 */
class ProviderThinkingModeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void explicitDisabledIsSentOnInitialAndToolContinuationRequests() throws Exception {
    try (var stub =
        new BootProviderFixture(
            (e, n) ->
                BootProviderFixture.reply(
                    e,
                    200,
                    n == 1
                        ? """
        {"id":"first","created":1,"model":"m","choices":[{"index":0,"message":{
          "role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function",
          "function":{"name":"http_get","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
        """
                        : BootProviderFixture.text()))) {
      configured(stub, "neutral")
          .withPropertyValues("aegisos.providers[0].thinking-mode=disabled")
          .run(
              c -> {
                assertThat(c).hasNotFailed();
                var service = c.getBean(ProviderService.class);
                var tools =
                    List.of(
                        new ToolSchema(
                            "http_get",
                            "读取已批准目标",
                            """
                {"type":"object","properties":{}}
                """));
                var user = new ModelMessage(ModelMessage.Role.USER, "使用工具后回答", null, List.of());
                var first =
                    service.chat(
                        new ModelRequest(
                            "neutral", "m", List.of(user), tools, new GenerationOptions(0.2, 256)));
                assertThat(first.toolCalls()).hasSize(1);
                // 运行中的配置Bean可被调用方修改，已注册客户端必须继续使用启动时的不可变快照。
                c.getBean(ProviderProperties.class)
                    .getProviders()
                    .getFirst()
                    .setThinkingMode("enabled");
                service.chat(
                    new ModelRequest(
                        "neutral",
                        "m",
                        List.of(
                            user,
                            new ModelMessage(
                                ModelMessage.Role.ASSISTANT,
                                first.content(),
                                null,
                                first.toolCalls()),
                            new ModelMessage(
                                ModelMessage.Role.TOOL, "{\"ok\":true}", "call_1", List.of())),
                        tools,
                        new GenerationOptions(0.2, 256)));
                assertThat(stub.calls).hasValue(2);
                for (String body : stub.bodies) {
                  var tree = JSON.readTree(body);
                  assertThat(tree.path("thinking").path("type").asText()).isEqualTo("disabled");
                  assertThat(tree.path("model").asText()).isEqualTo("m");
                  assertThat(tree.path("temperature").asDouble()).isEqualTo(0.2);
                  assertThat(tree.path("tools").size()).isEqualTo(1);
                }
                var second = JSON.readTree(stub.bodies.get(1)).path("messages");
                assertThat(second.get(1).path("tool_calls").get(0).path("id").asText())
                    .isEqualTo(second.get(2).path("tool_call_id").asText());
              });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"neutral", "deepseek-main"})
  void absentModeNeverInfersAnExtensionFromProviderIdentity(String name) throws Exception {
    try (var stub =
        new BootProviderFixture(
            (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()))) {
      configured(stub, name)
          .run(
              c -> {
                c.getBean(ProviderService.class).chat(ModelRequest.text(name, "m", "x"));
                assertThat(stub.calls).hasValue(1);
                assertThat(JSON.readTree(stub.bodies.getFirst()).has("thinking")).isFalse();
              });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"enabled", "bad", "DISABLED", ""})
  void invalidModeIsUnavailableBeforeCredentialResolutionOrHttp(String mode) throws Exception {
    var credentials = new AtomicInteger();
    try (var stub =
        new BootProviderFixture(
            (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()))) {
      ProviderConfigurationTest.runner(
              n -> {
                credentials.incrementAndGet();
                return UUID.randomUUID().toString();
              })
          .withPropertyValues(properties(stub, "neutral"))
          .withPropertyValues("aegisos.providers[0].thinking-mode=" + mode)
          .run(
              c -> {
                assertThat(c).hasNotFailed();
                var registration =
                    c.getBean(ProviderRegistry.class).registrations().iterator().next();
                assertThat(registration.available()).isFalse();
                assertThat(registration.unavailableReason())
                    .isEqualTo(ProviderErrorCode.INVALID_CONFIGURATION);
                assertThatThrownBy(
                        () ->
                            c.getBean(ProviderService.class)
                                .chat(ModelRequest.text("neutral", "m", "x")))
                    .isInstanceOf(ProviderException.class);
                assertThat(credentials).hasValue(0);
                assertThat(stub.calls).hasValue(0);
              });
    }
  }

  private static org.springframework.boot.test.context.runner.ApplicationContextRunner configured(
      BootProviderFixture stub, String name) {
    return ProviderConfigurationTest.runner().withPropertyValues(properties(stub, name));
  }

  private static String[] properties(BootProviderFixture stub, String name) {
    return new String[] {
      "spring.profiles.active=test",
      "aegisos.providers[0].name=" + name,
      "aegisos.providers[0].adapter=openai-compatible",
      "aegisos.providers[0].models[0]=m",
      "aegisos.providers[0].credential-ref=env:SYNTHETIC_KEY",
      "aegisos.providers[0].base-url=" + stub.uri(),
      "aegisos.providers[0].read-timeout=5s"
    };
  }
}

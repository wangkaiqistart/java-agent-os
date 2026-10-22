package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.config.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderConfigurationTest {
  static ApplicationContextRunner runner() {
    return runner(n -> UUID.randomUUID().toString());
  }

  static ApplicationContextRunner runner(java.util.function.Function<String, String> source) {
    return new ApplicationContextRunner()
        .withUserConfiguration(ProviderConfiguration.class)
        .withPropertyValues("aegisos.workspace-root=" + temporaryWorkspace())
        .withBean(
            LlmCallAuditStore.class,
            () ->
                new LlmCallAuditStore() {
                  public AuditHandle begin(AuditStart s) {
                    return new AuditHandle(UUID.randomUUID());
                  }

                  public void complete(AuditHandle h, AuditCompletion c) {}

                  public void reject(AuditRejection r) {}
                })
        .withBean(
            EnvironmentCredentialResolver.class, () -> new EnvironmentCredentialResolver(source));
  }

  private static String temporaryWorkspace() {
    try {
      return java.nio.file.Files.createTempDirectory("provider-config-").toString();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void defaultValuesAndNoStartupProbe() {
    runner()
        .withPropertyValues(
            "aegisos.providers[0].name=p",
            "aegisos.providers[0].adapter=openai-compatible",
            "aegisos.providers[0].models[0]=m",
            "aegisos.providers[0].credential-ref=env:KEY")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              var d =
                  c.getBean(ProviderRegistry.class).registrations().iterator().next().definition();
              assertThat(d.baseUrl().toString()).isEqualTo("https://api.openai.com");
              assertThat(d.maxRetries()).isZero();
              assertThat(d.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(d.readTimeout()).isEqualTo(Duration.ofSeconds(60));
            });
  }

  @Test
  void duplicateNamesPreventStartup() {
    runner()
        .withPropertyValues("aegisos.providers[0].name=p", "aegisos.providers[1].name=p")
        .run(c -> assertThat(c).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "aegisos.providers[0].max-retries=-1",
        "aegisos.providers[0].max-retries=bad",
        "aegisos.providers[0].read-timeout=0s",
        "aegisos.providers[0].connect-timeout=-1s",
        "aegisos.providers[0].read-timeout=bad",
        "aegisos.providers[0].base-url=https://u:pass@example.org",
        "aegisos.providers[0].base-url=https://example.org?key=x",
        "aegisos.providers[0].base-url=https://example.org#x",
        "aegisos.providers[0].base-url=http://example.org",
        "aegisos.providers[0].base-url=https://example.org/v1/chat/completions",
        "aegisos.providers[0].credential-ref=plaintext"
      })
  void invalidFieldsDoNotBecomeUsable(String override) {
    runner()
        .withPropertyValues(
            "aegisos.providers[0].name=p",
            "aegisos.providers[0].adapter=openai-compatible",
            "aegisos.providers[0].models[0]=m",
            "aegisos.providers[0].credential-ref=env:KEY",
            override)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              var r = c.getBean(ProviderRegistry.class).registrations().iterator().next();
              assertThat(r.available()).isFalse();
              assertThatThrownBy(
                      () -> c.getBean(ProviderService.class).chat(ModelRequest.text("p", "m", "x")))
                  .isInstanceOf(ProviderException.class);
            });
  }

  @Test
  void singleMissingCredentialUnknownAdapterAndEmptyModelsAreUnavailable() {
    runner(n -> null)
        .withPropertyValues(
            "aegisos.providers[0].name=p",
            "aegisos.providers[0].adapter=openai-compatible",
            "aegisos.providers[0].models[0]=m",
            "aegisos.providers[0].credential-ref=env:KEY",
            "aegisos.providers[1].name=q",
            "aegisos.providers[1].adapter=unknown",
            "aegisos.providers[2].name=r")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c.getBean(ProviderRegistry.class).registrations())
                  .hasSize(3)
                  .allMatch(r -> !r.available());
            });
  }

  @Test
  void httpRequiresTestProfileAndLoopback() throws Exception {
    try (var stub =
        new BootProviderFixture(
            (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()))) {
      var common =
          runner()
              .withPropertyValues(
                  "aegisos.providers[0].name=p",
                  "aegisos.providers[0].adapter=openai-compatible",
                  "aegisos.providers[0].models[0]=m",
                  "aegisos.providers[0].credential-ref=env:KEY",
                  "aegisos.providers[0].base-url=" + stub.uri());
      common.run(
          c ->
              assertThat(c.getBean(ProviderRegistry.class).registrations())
                  .allMatch(r -> !r.available()));
      common
          .withPropertyValues("spring.profiles.active=test")
          .run(
              c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(ProviderRegistry.class).registrations())
                    .allMatch(ProviderRegistration::available);
                assertThat(stub.calls).hasValue(0);
              });
    }
  }

  @Test
  void envResolverRejectsInvalidReferencesAndReadsOnlyNamedVariable() {
    var names = new java.util.ArrayList<String>();
    var resolver =
        new EnvironmentCredentialResolver(
            n -> {
              names.add(n);
              return "value";
            });
    for (String ref : java.util.List.of("value", "env:", "env:BAD-KEY", "env:K\n", "file:/secret"))
      assertThat(resolver.resolve(ref)).isNull();
    assertThat(names).isEmpty();
    assertThat(resolver.resolve("env:MODEL_KEY")).isEqualTo("value");
    assertThat(names).containsExactly("MODEL_KEY");
  }
}

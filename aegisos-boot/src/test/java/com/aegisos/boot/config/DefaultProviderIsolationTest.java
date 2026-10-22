package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.TransportSendObserver;
import com.aegisos.provider.config.ProviderRegistry;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 使用有凭据且可用的 loopback 连接，观测默认装配零发送；显式调用证明计数夹具有效。 */
class DefaultProviderIsolationTest {
  @Test
  void populatedCredentialDoesNotProbeAtStartupAndDefaultBuildHasNoSmoke() throws Exception {
    try (var stub =
        new BootProviderFixture(
            (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()))) {
      var sends = new AtomicInteger();
      ProviderConfigurationTest.runner(n -> "synthetic-isolation-key")
          .withPropertyValues(
              "spring.profiles.active=test",
              "aegisos.providers[0].name=p",
              "aegisos.providers[0].adapter=openai-compatible",
              "aegisos.providers[0].models[0]=m",
              "aegisos.providers[0].credential-ref=env:DEEPSEEK_API_KEY",
              "aegisos.providers[0].base-url=" + stub.uri())
          .withBean(TransportSendObserver.class, () -> () -> sends.incrementAndGet())
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ProviderRegistry.class).get("p").available()).isTrue();
                assertThat(sends).hasValue(0);
                assertThat(stub.calls).hasValue(0);
                context.getBean(ProviderService.class).chat(ModelRequest.text("p", "m", "x"));
                assertThat(sends).hasValue(1);
                assertThat(stub.calls).hasValue(1);
              });
    }
    Path repo = Path.of(System.getProperty("user.dir")).getParent();
    var xml =
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(repo.resolve("aegisos-boot/pom.xml").toFile());
    var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
    assertThat(
            xpath.evaluate(
                "count(/project/build/plugins/plugin[artifactId='maven-failsafe-plugin'])", xml))
        .isEqualTo("0");
    assertThat(Files.readString(repo.resolve(".github/workflows/engineering.yml")))
        .doesNotContain("real-provider-smoke");
  }
}

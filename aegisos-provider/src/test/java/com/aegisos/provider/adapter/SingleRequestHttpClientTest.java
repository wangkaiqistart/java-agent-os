package com.aegisos.provider.adapter;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 观察器必须接在真实网络交换点；调用适配器次数不代表 HTTP 已发送。 */
class SingleRequestHttpClientTest {
  @Test
  void onePhysicalSendForSuccess503RedirectAndReadTimeout() throws Exception {
    for (int status : List.of(200, 503, 307, 0))
      try (var stub =
          new HttpStub(
              (e, n) -> {
                if (status == 0) Thread.sleep(500);
                if (status == 503) e.getResponseHeaders().set("Retry-After", "0");
                if (status == 307) e.getResponseHeaders().set("Location", "/second");
                HttpStub.reply(e, status == 0 ? 200 : status, HttpStub.text());
              })) {
        var sends = new AtomicInteger();
        try (var adapter =
            new OpenAiClientFactory()
                .create(
                    ProviderFixtures.definition(stub, 0),
                    UUID.randomUUID().toString(),
                    () -> sends.incrementAndGet())) {
          if (status == 200)
            assertThat(adapter.chat(ModelRequest.text("p", "m", "x")).content()).isNotBlank();
          else
            assertThatThrownBy(() -> adapter.chat(ModelRequest.text("p", "m", "x")))
                .isInstanceOf(ProviderException.class);
          assertThat(sends).hasValue(1);
          assertThat(stub.calls).hasValue(1);
        }
      }
  }

  @Test
  void connectFailureHasNoHttpHeaderSendAndNoReplay() throws Exception {
    var sends = new AtomicInteger();
    var closed = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
    int port = closed.getLocalPort();
    closed.close();
    var definition =
        new com.aegisos.provider.config.ProviderDefinition(
            "p",
            "openai-compatible",
            Set.of("m"),
            java.net.URI.create("http://127.0.0.1:" + port),
            "env:KEY",
            0,
            java.time.Duration.ofMillis(120),
            java.time.Duration.ofMillis(120));
    try (var adapter =
        new OpenAiClientFactory()
            .create(definition, UUID.randomUUID().toString(), () -> sends.incrementAndGet())) {
      assertThatThrownBy(() -> adapter.chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOf(ProviderException.class);
      assertThat(sends).hasValue(0);
    }
  }
}

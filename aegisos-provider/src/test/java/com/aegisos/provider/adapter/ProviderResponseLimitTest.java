package com.aegisos.provider.adapter;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderResponseLimitTest {
  @Test
  void oversizedSuccessfulAndErrorBodiesWithOrWithoutLengthAreProtocolFailures() throws Exception {
    for (int code : new int[] {200, 503})
      for (boolean length : new boolean[] {false, true}) {
        String body = HttpStub.text().replace("你好", "a".repeat(1048577));
        try (var stub =
                new HttpStub(
                    (e, n) -> {
                      byte[] b = body.getBytes(StandardCharsets.UTF_8);
                      e.getResponseHeaders().set("Content-Type", "application/json");
                      e.sendResponseHeaders(code, length ? b.length : 0);
                      e.getResponseBody().write(b);
                    });
            var adapter =
                new OpenAiClientFactory()
                    .create(
                        ProviderFixtures.definition(stub, 0, java.time.Duration.ofSeconds(5)),
                        UUID.randomUUID().toString())) {
          assertThatThrownBy(() -> adapter.chat(ModelRequest.text("p", "m", "safe")))
              .isInstanceOfSatisfying(
                  ProviderException.class,
                  e -> {
                    assertThat(e.failure().code())
                        .isEqualTo(ProviderErrorCode.PROVIDER_PROTOCOL_ERROR);
                    assertThat(e.failure().retryable()).isFalse();
                    assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.RESPONDED);
                  });
          assertThat(stub.calls).hasValue(1);
        }
      }
  }
}

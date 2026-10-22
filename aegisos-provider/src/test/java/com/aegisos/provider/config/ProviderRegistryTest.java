package com.aegisos.provider.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.*;
import com.aegisos.provider.service.*;
import com.aegisos.provider.support.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {
  ProviderRegistration named(HttpStub stub, String name) {
    var old = ProviderFixtures.definition(stub, 0);
    var d =
        new ProviderDefinition(
            name,
            old.adapter(),
            old.models(),
            old.baseUrl(),
            old.credentialRef(),
            old.maxRetries(),
            old.connectTimeout(),
            old.readTimeout());
    return new ProviderRegistration(
        d, new OpenAiClientFactory().create(d, UUID.randomUUID().toString()), null);
  }

  @Test
  void sameAdapterRoutesOnlyToExplicitNameAndRegistryIsImmutable() throws Exception {
    try (var a = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()));
        var b = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var entries = new ArrayList<>(List.of(named(a, "A"), named(b, "B")));
      try (var registry = new ProviderRegistry(entries)) {
        entries.clear();
        var audit = new ProviderFixtures.Audit();
        var service = new DefaultProviderService(registry.registrations(), audit, () -> {});
        service.chat(ModelRequest.text("A", "m", "a"));
        assertThat(a.calls).hasValue(1);
        assertThat(b.calls).hasValue(0);
        service.chat(ModelRequest.text("B", "m", "b"));
        assertThat(a.calls).hasValue(1);
        assertThat(b.calls).hasValue(1);
        assertThatThrownBy(() -> registry.registrations().clear())
            .isInstanceOf(UnsupportedOperationException.class);
      }
    }
  }

  @Test
  void missingUnknownNamesAndModelsNeverFallback() throws Exception {
    try (var a = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()));
        var registry = new ProviderRegistry(List.of(named(a, "A")))) {
      var audit = new ProviderFixtures.Audit();
      var service = new DefaultProviderService(registry.registrations(), audit, () -> {});
      String[][] cases = {
        {null, "m", "PROVIDER_REQUIRED"},
        {"", "m", "PROVIDER_REQUIRED"},
        {"a", "m", "UNKNOWN_PROVIDER"},
        {"unknown", "m", "UNKNOWN_PROVIDER"},
        {"A", null, "MODEL_REQUIRED"},
        {"A", "", "MODEL_REQUIRED"},
        {"A", "other", "MODEL_UNAVAILABLE"},
        {" A", "m", "INVALID_REQUEST"},
        {"A", " m", "INVALID_REQUEST"}
      };
      for (var item : cases)
        assertThatThrownBy(() -> service.chat(ModelRequest.text(item[0], item[1], "x")))
            .isInstanceOfSatisfying(
                ProviderException.class,
                e -> {
                  assertThat(e.failure().code().name()).isEqualTo(item[2]);
                  assertThat(e.failure().attemptCount()).isZero();
                  assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.NOT_SENT);
                });
      assertThat(a.calls).hasValue(0);
      assertThat(audit.rejected).hasSize(cases.length);
      assertThat(audit.starts).isEmpty();
    }
  }

  @Test
  void duplicateNamesFailAndUnavailableRegistrationDoesNotFallback() throws Exception {
    try (var a = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var r = named(a, "A");
      assertThatThrownBy(() -> new ProviderRegistry(List.of(r, r)))
          .isInstanceOf(IllegalArgumentException.class);
      try (var registry =
          new ProviderRegistry(
              List.of(
                  new ProviderRegistration(
                      r.definition(), null, ProviderErrorCode.PROVIDER_UNAVAILABLE)))) {
        assertThatThrownBy(
                () ->
                    new DefaultProviderService(
                            registry.registrations(), new ProviderFixtures.Audit(), () -> {})
                        .chat(ModelRequest.text("A", "m", "x")))
            .isInstanceOfSatisfying(
                ProviderException.class,
                e ->
                    assertThat(e.failure().code())
                        .isEqualTo(ProviderErrorCode.PROVIDER_UNAVAILABLE));
        assertThat(a.calls).hasValue(0);
      }
      r.adapter().close();
    }
  }
}

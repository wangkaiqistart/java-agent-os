package com.aegisos.provider.service;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderRetryTest {
  /** 有限重试的所有尝试共享会话与逻辑请求，但不把关联放进厂商请求。 */
  @Test
  void sessionPersistsAcrossTimeoutAndRecovery() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              if (n == 1) Thread.sleep(500);
              HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit = new ProviderFixtures.Audit();
      var response =
          ProviderFixtures.service(stub, 1, audit)
              .chat("Session-A", ModelRequest.text("p", "m", "x"));
      assertThat(audit.starts)
          .hasSize(2)
          .allSatisfy(
              start -> {
                assertThat(start.sessionId()).isEqualTo("Session-A");
                assertThat(start.requestId()).isEqualTo(response.requestId());
              });
      assertThat(stub.calls).hasValue(2);
      assertThat(stub.bodies).allSatisfy(body -> assertThat(body).doesNotContain("Session-A"));
    }
  }

  @Test
  void defaultDoesNotRetryTimeout() throws Exception {
    timeout(0, 1);
  }

  @Test
  void configuredBudgetIsNPlusOneAndExhaustionIsUnknown() throws Exception {
    timeout(2, 3);
  }

  void timeout(int budget, int count) throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              Thread.sleep(500);
              HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit = new ProviderFixtures.Audit();
      var service = ProviderFixtures.service(stub, budget, audit);
      assertThatThrownBy(() -> service.chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e -> {
                assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.PROVIDER_TIMEOUT);
                assertThat(e.failure().attemptCount()).isEqualTo(count);
                assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.UNKNOWN);
              });
      assertThat(stub.calls).hasValue(count);
      assertThat(audit.starts).hasSize(count);
      assertThat(audit.completed).hasSize(count);
      assertThat(audit.starts.stream().map(s -> s.attemptNo()).toList())
          .containsExactlyElementsOf(
              java.util.stream.LongStream.rangeClosed(1, count).boxed().toList());
      assertThat(audit.starts.stream().map(s -> s.requestId()).distinct().count()).isEqualTo(1);
    }
  }

  @Test
  void twoTimeoutsThenSuccessUsesOnlyThreeRequests() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              if (n < 3) Thread.sleep(500);
              HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit = new ProviderFixtures.Audit();
      var response =
          ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x"));
      assertThat(response.content()).isEqualTo("你好");
      assertThat(stub.calls).hasValue(3);
      assertThat(audit.completed).hasSize(3);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 408, 429, 500, 502, 503, 307})
  void httpResponsesNeverRetry(int status) throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              e.getResponseHeaders().set("Location", "/redirect");
              HttpStub.reply(e, status, "{\"error\":{\"message\":\"unsafe\",\"type\":\"test\"}}");
            })) {
      var audit = new ProviderFixtures.Audit();
      assertThatThrownBy(
              () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e -> {
                assertThat(e.failure().retryable()).isFalse();
                assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.RESPONDED);
                assertThat(e.failure().attemptCount()).isEqualTo(1);
              });
      assertThat(stub.calls).hasValue(1);
    }
  }

  @Test
  void malformedResponseDoesNotRetry() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, "{broken"))) {
      assertThatThrownBy(
              () ->
                  ProviderFixtures.service(stub, 2, new ProviderFixtures.Audit())
                      .chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOf(ProviderException.class);
      assertThat(stub.calls).hasValue(1);
    }
  }

  @Test
  void realDisconnectIsRetriedOnlyByOuterBudget() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              if (n < 3) e.close();
              else HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit = new ProviderFixtures.Audit();
      var response =
          ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x"));
      assertThat(response.content()).isEqualTo("你好");
      assertThat(stub.calls).hasValue(3);
      assertThat(audit.completed).hasSize(3);
    }
  }

  @Test
  void maximumIntBudgetDoesNotOverflowToZeroAttempts() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 401, "{}"))) {
      assertThatThrownBy(
              () ->
                  ProviderFixtures.service(stub, Integer.MAX_VALUE, new ProviderFixtures.Audit())
                      .chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class, e -> assertThat(e.failure().attemptCount()).isEqualTo(1));
      assertThat(stub.calls).hasValue(1);
    }
  }

  @Test
  void retryAfterZeroCannotTriggerHiddenPostReplay() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              e.getResponseHeaders().set("Retry-After", "0");
              HttpStub.reply(e, 503, "{}");
            })) {
      var audit = new ProviderFixtures.Audit();
      assertThatThrownBy(
              () -> ProviderFixtures.service(stub, 0, audit).chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e -> assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.PROVIDER_HTTP_ERROR));
      assertThat(stub.calls).hasValue(1);
      assertThat(audit.starts).hasSize(1);
      assertThat(audit.completed).hasSize(1);
    }
  }
}

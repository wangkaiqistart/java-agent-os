package com.aegisos.provider.service;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.support.*;
import org.junit.jupiter.api.Test;

class ProviderAuditFailureTest {
  @Test
  void firstBeginFailureIsNotSentAndMakesNoHttpRequest() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var audit =
          new ProviderFixtures.Audit() {
            @Override
            public AuditHandle begin(AuditStart s) {
              throw new AuditUnavailableException();
            }
          };
      assertFailure(
          () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")),
          ProviderErrorCode.AUDIT_UNAVAILABLE,
          ExternalOutcome.NOT_SENT,
          0);
      assertThat(stub.calls).hasValue(0);
      assertThat(audit.completed).isEmpty();
    }
  }

  @Test
  void fullUnauthorizedResponseIsRespondedButTimeoutThenUnauthorizedStaysUnknown()
      throws Exception {
    for (boolean timeoutFirst : new boolean[] {false, true})
      try (var stub =
          new HttpStub(
              (e, n) -> {
                if (timeoutFirst && n == 1) Thread.sleep(500);
                HttpStub.reply(e, 401, "{}");
              })) {
        var audit = new ProviderFixtures.Audit();
        assertFailure(
            () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")),
            ProviderErrorCode.AUTHENTICATION_FAILED,
            timeoutFirst ? ExternalOutcome.UNKNOWN : ExternalOutcome.RESPONDED,
            timeoutFirst ? 2 : 1);
        assertThat(stub.calls).hasValue(timeoutFirst ? 2 : 1);
        assertThat(audit.completed).hasSize(timeoutFirst ? 2 : 1);
      }
  }

  @Test
  void timeoutThenBeginFailureKeepsPreviousAttemptAndUnknown() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              Thread.sleep(500);
              HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit =
          new ProviderFixtures.Audit() {
            @Override
            public AuditHandle begin(AuditStart s) {
              if (s.attemptNo() == 2) throw new AuditUnavailableException();
              return super.begin(s);
            }
          };
      assertFailure(
          () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")),
          ProviderErrorCode.AUDIT_UNAVAILABLE,
          ExternalOutcome.UNKNOWN,
          1);
      assertThat(stub.calls).hasValue(1);
      assertThat(audit.starts).hasSize(1);
      assertThat(audit.completed).hasSize(1);
    }
  }

  @Test
  void successOrFailureFollowedByCompletionFaultNeverReturnsSuccessOrRetries() throws Exception {
    for (boolean timeout : new boolean[] {false, true})
      try (var stub =
          new HttpStub(
              (e, n) -> {
                if (timeout) Thread.sleep(500);
                HttpStub.reply(e, 200, HttpStub.text());
              })) {
        var audit =
            new ProviderFixtures.Audit() {
              @Override
              public void complete(AuditHandle h, AuditCompletion c) {
                throw new AuditUnavailableException();
              }
            };
        assertFailure(
            () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")),
            ProviderErrorCode.AUDIT_UNAVAILABLE,
            ExternalOutcome.UNKNOWN,
            1);
        assertThat(stub.calls).hasValue(1);
        assertThat(audit.starts).hasSize(1);
        assertThat(audit.completed).isEmpty();
      }
  }

  @Test
  void failedRejectIsAuditUnavailableWithoutPretendingPersistence() throws Exception {
    try (var stub = new HttpStub((e, n) -> HttpStub.reply(e, 200, HttpStub.text()))) {
      var audit =
          new ProviderFixtures.Audit() {
            @Override
            public void reject(AuditRejection r) {
              throw new AuditUnavailableException();
            }
          };
      assertFailure(
          () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", null, "x")),
          ProviderErrorCode.AUDIT_UNAVAILABLE,
          ExternalOutcome.NOT_SENT,
          0);
      assertThat(stub.calls).hasValue(0);
      assertThat(audit.rejected).isEmpty();
    }
  }

  @Test
  void repeatedTimeoutExhaustionIsUnknown() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) -> {
              Thread.sleep(500);
              HttpStub.reply(e, 200, HttpStub.text());
            })) {
      var audit = new ProviderFixtures.Audit();
      assertFailure(
          () -> ProviderFixtures.service(stub, 2, audit).chat(ModelRequest.text("p", "m", "x")),
          ProviderErrorCode.PROVIDER_TIMEOUT,
          ExternalOutcome.UNKNOWN,
          3);
      assertThat(stub.calls).hasValue(3);
      assertThat(audit.completed).hasSize(3);
    }
  }

  @Test
  void upstreamModelRetirementIsModelUnavailableAndDoesNotRetry() throws Exception {
    try (var stub =
        new HttpStub(
            (e, n) ->
                HttpStub.reply(
                    e,
                    400,
                    "{\"error\":{\"code\":\"model_not_found\",\"message\":\"retired\"}}"))) {
      assertFailure(
          () ->
              ProviderFixtures.service(stub, 2, new ProviderFixtures.Audit())
                  .chat(ModelRequest.text("p", "m", "x")),
          ProviderErrorCode.MODEL_UNAVAILABLE,
          ExternalOutcome.RESPONDED,
          1);
      assertThat(stub.calls).hasValue(1);
    }
  }

  private void assertFailure(
      Runnable action, ProviderErrorCode code, ExternalOutcome outcome, long attempts) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ProviderException.class,
            e -> {
              assertThat(e.failure().requestId()).isNotNull();
              assertThat(e.failure().code()).isEqualTo(code);
              assertThat(e.failure().externalOutcome()).isEqualTo(outcome);
              assertThat(e.failure().attemptCount()).isEqualTo(attempts);
              assertThat(e.getCause()).isNull();
            });
  }
}

package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.provider.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessingCorrelationTest {
  @Test
  void preparedTurnLinksEveryPhysicalAuditAtomicallyAndCannotBeRebound() throws Exception {
    try (var fixture = new ReactRuntimeFixture();
        var runtime = fixture.openRuntime()) {
      String session = runtime.agents().createSession("p").sessionId();
      var handle = runtime.memory().beginProcessing(session, "p", "request", UUID.randomUUID());
      handle = runtime.memory().prepareModelTurn(handle, 1);
      var provider = runtime.context().getBean(ProfileProviderService.class);
      var result =
          provider.chatProfile(
              session,
              "p",
              ProfilePrompt.text("request"),
              new ProviderInvocation(handle.processId(), 1));
      try (var c = fixture.reopen();
          var s = c.createStatement();
          var rows =
              s.executeQuery(
                  "select l.process_id,l.iteration_no,a.request_id,a.session_id from llm_request_links l join llm_calls a on l.request_id=a.request_id")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(handle.processId().toString());
        assertThat(rows.getInt(2)).isEqualTo(1);
        assertThat(rows.getString(3)).isEqualTo(result.requestId().toString());
        assertThat(rows.getString(4)).isEqualTo(session);
        assertThat(rows.next()).isFalse();
      }
      int before = fixture.httpRequests.get();
      var invalid = new ProviderInvocation(UUID.randomUUID(), 1);
      assertThatThrownBy(
              () -> provider.chatProfile(session, "p", ProfilePrompt.text("request"), invalid))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e -> assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.INVALID_REQUEST));
      assertThat(fixture.httpRequests).hasValue(before);
      try (var c = fixture.reopen();
          var s = c.createStatement();
          var rows =
              s.executeQuery(
                  "select count(*) from llm_calls where status='REJECTED' and session_id is null and error_code='INVALID_REQUEST'")) {
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void validInvocationWithLinkPersistenceFailureHasZeroExternalSends() throws Exception {
    try (var fixture = new ReactRuntimeFixture();
        var runtime = fixture.openRuntime()) {
      String session = runtime.agents().createSession("p").sessionId();
      var handle = runtime.memory().beginProcessing(session, "p", "request", UUID.randomUUID());
      handle = runtime.memory().prepareModelTurn(handle, 1);
      var invocation = new ProviderInvocation(handle.processId(), 1);
      try (var c = fixture.reopen();
          var s = c.createStatement()) {
        s.execute("drop table llm_request_links");
      }
      var provider = runtime.context().getBean(ProfileProviderService.class);
      assertThatThrownBy(
              () -> provider.chatProfile(session, "p", ProfilePrompt.text("request"), invocation))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e -> assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.AUDIT_UNAVAILABLE));
      assertThat(fixture.httpRequests).hasValue(0);
      try (var c = fixture.reopen();
          var s = c.createStatement();
          var rows = s.executeQuery("select count(*) from llm_calls")) {
        assertThat(rows.getInt(1)).isZero();
      }
    }
  }
}

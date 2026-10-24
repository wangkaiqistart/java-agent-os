package com.aegisos.core.agent;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuntimeContractTest {
  @Test
  void snapshotsFreezeCollectionsAndNeverPrintBodies() {
    var messages = new ArrayList<ModelMessage>();
    messages.add(new ModelMessage(ModelMessage.Role.USER, "private-conversation", null, List.of()));
    var session =
        new SessionSnapshot(
            UUID.randomUUID().toString(), "p", Instant.EPOCH, Instant.EPOCH, 0, messages);
    messages.clear();
    assertThat(session.messages()).hasSize(1);
    assertThatThrownBy(() -> session.messages().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(session.toString()).doesNotContain("private-conversation");
    assertThat(new AgentRequest(session.sessionId(), "p", "private-conversation").toString())
        .doesNotContain("private-conversation");
  }

  @Test
  void noCompletedResultBeforeCommit() {
    assertThatThrownBy(
            () ->
                new ProcessingResult(
                    null, null, ProcessingStatus.COMPLETED, 1, "reply", null, false))
        .hasMessage("INVALID_RESULT");
  }

  @Test
  void invalidToolIdentityIsNeverEchoed() {
    assertThatThrownBy(
            () ->
                new ToolObservation(
                    UUID.randomUUID(),
                    "unsafe identity",
                    "http_get",
                    false,
                    AgentErrorCode.INVALID_ARGUMENTS,
                    ExternalOutcome.NOT_SENT,
                    null))
        .hasMessage("INVALID_RESPONSE");
  }

  @Test
  void oldAuditConstructorsRetainNullCorrelation() {
    assertThat(
            new com.aegisos.core.audit.AuditStart(UUID.randomUUID(), 1, "p", "m", Instant.EPOCH)
                .invocation())
        .isNull();
  }
}

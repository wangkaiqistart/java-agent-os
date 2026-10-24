package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContextEventsPersistenceTest {
  @Test
  void optionalMissingWarningsAreCommittedAndEventFailurePreventsProvider() throws Exception {
    for (boolean broken : new boolean[] {false, true})
      try (var f = new ReactRuntimeFixture();
          var r = f.openRuntime()) {
        if (broken)
          try (var c = f.reopen();
              var s = c.createStatement()) {
            s.execute(
                "CREATE TRIGGER fail_event BEFORE INSERT ON processing_events BEGIN SELECT RAISE(ABORT,'FIXTURE_FAILURE'); END");
          }
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        if (f.secureReadsSupported()) {
          assertThat(result.errorCode())
              .isEqualTo(broken ? AgentErrorCode.PERSISTENCE_UNCONFIRMED : null);
          assertThat(f.httpRequests).hasValue(broken ? 0 : 1);
          if (!broken)
            try (var c = f.reopen();
                var s = c.createStatement();
                var rows =
                    s.executeQuery(
                        "select count(*) from processing_events where event_type='WARN' and reason_code='OPTIONAL_FILE_MISSING'")) {
              assertThat(rows.getInt(1)).isEqualTo(3);
            }
        } else
          assertThat(result.errorCode())
              .isEqualTo(
                  broken
                      ? AgentErrorCode.PERSISTENCE_UNCONFIRMED
                      : AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      }
  }

  @Test
  void safeOversizedOptionalMemoryIsRemovedWithDurableTrimEvent() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      f.customize =
          c ->
              c.registerBean(
                  com.aegisos.core.memory.LongTermMemoryStore.class,
                  () -> q -> Optional.of("中".repeat(100000)));
      try (var r = f.openRuntime()) {
        String session = r.agents().createSession("p").sessionId();
        var result = r.agents().process(new AgentRequest(session, "p", "safe"));
        if (f.secureReadsSupported()) {
          assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
          try (var c = f.reopen();
              var s = c.createStatement();
              var rows =
                  s.executeQuery(
                      "select count(*) from processing_events where event_type='TRUNCATED' and reason_code='OPTIONAL_MEMORY_TRIMMED'")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
          }
        } else assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      }
    }
  }
}

package com.aegisos.storage.session;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.provider.*;
import com.aegisos.storage.audit.AuditStorageConfiguration;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextHistoryQueryTest {
  @TempDir Path root;

  @Test
  void latestTwentyCompletedProcessingsAreOrderedAndQueryDoesNotDeleteHistory() throws Exception {
    var config = new AuditStorageConfiguration();
    try (var ds = config.dataSource(root.resolve("history.db").toString(), 1000)) {
      var factory = config.entityManagerFactory(ds);
      factory.afterPropertiesSet();
      try {
        var store =
            new JpaSessionStore(
                factory.getObject(),
                config.transactionManager(factory.getObject()),
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));
        var session = store.createSession("p");
        for (int i = 0; i < 25; i++) {
          var h = store.beginProcessing(session.sessionId(), "p", "user" + i, UUID.randomUUID());
          h = store.prepareModelTurn(h, 1);
          var request = UUID.randomUUID();
          try (var c = ds.getConnection();
              var s = c.prepareStatement("INSERT INTO llm_request_links VALUES(?,?,1)")) {
            s.setString(1, request.toString());
            s.setString(2, h.processId().toString());
            s.executeUpdate();
          }
          var accepted =
              store.acceptModelResponse(
                  h, 1, new ModelResponse(request, "p", "m", "reply" + i, List.of(), "stop", null));
          store.finish(accepted.handle(), ProcessingStatus.COMPLETED, null, "reply" + i);
        }
        var failed = store.beginProcessing(session.sessionId(), "p", "failed", UUID.randomUUID());
        store.finish(failed, ProcessingStatus.FAILED, AgentErrorCode.PROVIDER_FAILURE, null);
        var current = store.beginProcessing(session.sessionId(), "p", "current", UUID.randomUUID());
        var history = store.loadContextHistory(current, 20);
        assertThat(history.completedProcessings()).hasSize(20);
        assertThat(
                history
                    .completedProcessings()
                    .getFirst()
                    .getFirst()
                    .messages()
                    .getFirst()
                    .content())
            .isEqualTo("user5");
        assertThat(
                history.completedProcessings().getLast().getFirst().messages().getFirst().content())
            .isEqualTo("user24");
        assertThat(history.currentGroups()).hasSize(1);
        try (var c = ds.getConnection();
            var s = c.createStatement();
            var rows = s.executeQuery("select count(*) from session_messages")) {
          assertThat(rows.getInt(1)).isEqualTo(52);
        }
      } finally {
        factory.destroy();
      }
    }
  }
}

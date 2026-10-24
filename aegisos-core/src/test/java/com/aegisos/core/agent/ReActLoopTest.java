package com.aegisos.core.agent;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.support.ReactTestFixtures;
import com.aegisos.core.tool.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReActLoopTest extends AgentServiceTest {
  int maximum = 10;

  @Override
  Profile profile() {
    var p = super.profile();
    return new Profile(
        p.name(),
        null,
        null,
        p.provider(),
        List.of("http_get"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Settings(maximum, null, Map.of()));
  }

  @Test
  void nOneTwoTenCountsAcceptedResponsesAndNeverExecutesLastRoundTools() throws Exception {
    for (int n : new int[] {1, 2, 10})
      for (boolean lastText : new boolean[] {false, true}) {
        maximum = n;
        responses.set(0);
        operations.clear();
        var calls = new AtomicInteger();
        executor =
            (c, t) -> {
              calls.incrementAndGet();
              return new ToolObservation(
                  UUID.randomUUID(),
                  t.id(),
                  t.name(),
                  true,
                  null,
                  ExternalOutcome.RESPONDED,
                  "safe");
            };
        var sequence = new ModelResponse[n];
        for (int i = 0; i < n; i++)
          sequence[i] =
              lastText && i == n - 1
                  ? ReactTestFixtures.text("done")
                  : new ModelResponse(
                      UUID.randomUUID(),
                      "provider",
                      "model",
                      "文本与工具同时出现",
                      List.of(
                          new ToolCall(
                              "id" + i, "http_get", "{\"url\":\"https://example.org/weather\"}")),
                      "tool_calls",
                      null);
        var provider = new ReactTestFixtures.SequenceProvider(sequence);
        var result = service(provider).process(new AgentRequest(session, "p", "safe"));
        if (supported()) {
          assertThat(result.status())
              .isEqualTo(lastText ? ProcessingStatus.COMPLETED : ProcessingStatus.MAX_ITERATIONS);
          assertThat(result.iterationCount()).isEqualTo(n);
          assertThat(provider.logicalRequests).hasValue(n);
          assertThat(calls).hasValue(n - 1);
        } else {
          assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
          assertThat(provider.logicalRequests).hasValue(0);
        }
        assertThat(ProfileContext.current()).isNull();
      }
  }
}

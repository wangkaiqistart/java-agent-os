package com.aegisos.tool;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {
  final ToolArgumentsParser parser =
      new ToolArgumentsParser(
          new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024),
          new DefaultSensitiveContentGuard(List.of("synthetic-tool-key-123")));

  @Test
  void strictArgumentsAndStableFingerprint() {
    var a = parser.parse("http_get", "{ \"url\": \"https://example.org/weather?q=1\" }");
    var b = parser.parse("http_get", "{\"url\":\"https://example.org/weather?q=1\"}");
    assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).matches("[0-9a-f]{64}");
    assertThat(a.values()).containsEntry("url", "https://example.org/weather?q=1");
  }

  @Test
  void malformedUnknownDuplicateTrailingAndSensitiveAreRejected() {
    for (String json :
        List.of(
            "[]",
            "{}",
            "{\"url\":1}",
            "{\"url\":\"x\",\"extra\":1}",
            "{\"url\":\"x\",\"url\":\"y\"}",
            "{\"url\":\"x\"}{}"))
      assertThatThrownBy(() -> parser.parse("http_get", json)).hasMessage("INVALID_ARGUMENTS");
    assertThatThrownBy(() -> parser.parse("http_get", "{\"url\":\"synthetic-tool-key-123\"}"))
        .hasMessage("SENSITIVE_CONTENT");
  }

  @Test
  void executorRejectsUnknownUnauthorizedAndInvalidBeforeAnyExecution() {
    for (int kind = 0; kind < 3; kind++) {
      var f = new ToolDeduplicationTest.Fixture();
      var c = f.context;
      if (kind == 1) {
        var p = c.profile();
        var deniedProfile =
            new com.aegisos.core.profile.Profile(
                p.name(),
                null,
                null,
                p.provider(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
        c = new com.aegisos.core.tool.ToolExecutionContext(c.handle(), deniedProfile, 1, 1);
      }
      var call =
          kind == 0
              ? new com.aegisos.core.provider.ToolCall("id", "unknown", "{}")
              : kind == 2
                  ? new com.aegisos.core.provider.ToolCall("id", "http_get", "{}")
                  : f.call("https://example.org/weather");
      var o = f.executor().execute(c, call);
      assertThat(o.success()).isFalse();
      assertThat(f.executions).hasValue(0);
      assertThat(f.records.get("id").status()).isEqualTo("REJECTED");
    }
  }
}

package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.channel.cli.CliChannel;
import com.aegisos.cli.ChatCommand;
import com.aegisos.core.agent.*;
import com.aegisos.core.context.SensitiveContentGuard;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliConversationTest {
  @Test
  void newThenRestoredConversationUsesSameSessionAndSafeJson() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      String session;
      try (var runtime = f.openRuntime()) {
        var channel = new CliChannel(runtime.context().getBean(SensitiveContentGuard.class), 32768);
        var out = new ByteArrayOutputStream();
        int exit =
            new ChatCommand(runtime.agents(), channel)
                .run(
                    "p",
                    null,
                    new ByteArrayInputStream("你好".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out));
        assertThat(exit).isEqualTo(f.secureReadsSupported() ? 0 : 5);
        var json = new tools.jackson.databind.ObjectMapper().readTree(out.toByteArray());
        session = json.get("sessionId").asText();
        assertThat(json.size()).isEqualTo(7);
      }
      try (var runtime = f.openRuntime()) {
        var channel = new CliChannel(runtime.context().getBean(SensitiveContentGuard.class), 32768);
        var out = new ByteArrayOutputStream();
        int exit =
            new ChatCommand(runtime.agents(), channel)
                .run(
                    "p",
                    session,
                    new ByteArrayInputStream("继续".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out));
        assertThat(exit).isEqualTo(f.secureReadsSupported() ? 0 : 5);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(session);
      }
      try (var c = f.reopen();
          var s = c.createStatement();
          var rows = s.executeQuery("SELECT count(*) FROM sessions")) {
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
    }
  }

  @Test
  void exitCodesAreStable() {
    assertThat(
            ChatCommand.exitCode(
                new ProcessingResult(null, null, ProcessingStatus.COMPLETED, 1, "ok", null, true)))
        .isZero();
    for (var pair :
        new Object[][] {
          {AgentErrorCode.UNKNOWN_SESSION, 2},
          {AgentErrorCode.SESSION_PROFILE_MISMATCH, 2},
          {AgentErrorCode.SESSION_BUSY, 3},
          {AgentErrorCode.MAX_ITERATIONS, 4},
          {AgentErrorCode.AUDIT_UNAVAILABLE, 5},
          {AgentErrorCode.PERSISTENCE_UNCONFIRMED, 5}
        })
      assertThat(
              ChatCommand.exitCode(
                  new ProcessingResult(
                      null,
                      null,
                      ProcessingStatus.FAILED,
                      0,
                      null,
                      (AgentErrorCode) pair[0],
                      false)))
          .isEqualTo(pair[1]);
  }
}

package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.config.CliApplicationConfiguration;
import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.cli.ChatCommand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliReplWiringTest {
  void configure(ReactRuntimeFixture f) {
    f.customize =
        c -> {
          c.getEnvironment()
              .getPropertySources()
              .addFirst(
                  new org.springframework.core.env.MapPropertySource(
                      "cli", java.util.Map.of("aegisos.cli.enabled", "true")));
          c.register(CliApplicationConfiguration.class);
        };
  }

  int run(ChatCommand c, String input) {
    return c.runInteractive(
        "p",
        null,
        "local",
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(new ByteArrayOutputStream()));
  }

  @Test
  void quitAndEofBeforeFirstMessageNeverCreateSession() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      configure(f);
      try (var runtime = f.openRuntime()) {
        var c = runtime.context().getBean(ChatCommand.class);
        assertThat(run(c, " /quit \n")).isZero();
        assertThat(run(c, "")).isZero();
      }
      assertThat(f.physicalSends).hasValue(0);
      try (var connection = f.reopen();
          var q = connection.createStatement()) {
        for (String table :
            java.util.List.of("sessions", "agent_processings", "llm_calls", "tool_invocations"))
          try (var rs = q.executeQuery("select count(*) from " + table)) {
            assertThat(rs.getInt(1)).as(table).isZero();
          }
      }
    }
  }

  @Test
  void twoMessagesUseOneBoundSessionAndQuitAddsNoProcessing() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      configure(f);
      boolean secure = f.secureReadsSupported();
      try (var runtime = f.openRuntime()) {
        int exit = run(runtime.context().getBean(ChatCommand.class), "第一条\n第二条\n/quit\n");
        assertThat(exit).isEqualTo(secure ? 0 : 5);
      }
      try (var connection = f.reopen();
          var q = connection.createStatement()) {
        try (var rs = q.executeQuery("select count(*) from sessions")) {
          assertThat(rs.getInt(1)).isEqualTo(1);
        }
        try (var rs =
            q.executeQuery("select count(*),count(distinct session_id) from agent_processings")) {
          assertThat(rs.getInt(1)).isEqualTo(secure ? 2 : 1);
          assertThat(rs.getInt(2)).isEqualTo(1);
        }
      }
      assertThat(f.physicalSends).hasValue(secure ? 2 : 0);
      if (secure) {
        assertThat(f.modelBodies.get(1)).contains("第一条", "第二条", "安全回复");
      }
    }
  }

  @Test
  void sensitiveInputAndBrokenPromptCreateNoSessionAndReleaseResources() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      configure(f);
      try (var runtime = f.openRuntime()) {
        var c = runtime.context().getBean(ChatCommand.class);
        assertThat(run(c, "synthetic-runtime-credential-123\n/quit\n")).isEqualTo(2);
        var broken =
            new PrintStream(
                new OutputStream() {
                  public void write(int value) throws IOException {
                    throw new IOException("synthetic");
                  }
                });
        assertThat(
                c.runInteractive(
                    "p",
                    null,
                    "local",
                    new ByteArrayInputStream("hello\n".getBytes()),
                    new PrintStream(new ByteArrayOutputStream()),
                    broken))
            .isEqualTo(5);
      }
      try (var reopened = f.openRuntime()) {
        assertThat(run(reopened.context().getBean(ChatCommand.class), "/quit\n")).isZero();
      }
      assertThat(f.physicalSends).hasValue(0);
      try (var connection = f.reopen();
          var q = connection.createStatement();
          var r = q.executeQuery("select count(*) from sessions")) {
        assertThat(r.getInt(1)).isZero();
      }
    }
  }

  @Test
  void auditFailureStopsWithoutProviderOrHiddenRetry() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      configure(f);
      try (var runtime = f.openRuntime()) {
        try (var c = f.reopen();
            var q = c.createStatement()) {
          q.execute(
              "CREATE TRIGGER fail_audit BEFORE INSERT ON llm_calls BEGIN SELECT RAISE(ABORT,'synthetic'); END");
        }
        assertThat(run(runtime.context().getBean(ChatCommand.class), "hello\nsecond\n/quit\n"))
            .isEqualTo(5);
      }
      assertThat(f.physicalSends).hasValue(0);
      try (var c = f.reopen();
          var q = c.createStatement();
          var r = q.executeQuery("select count(*) from agent_processings")) {
        assertThat(r.getInt(1)).isEqualTo(1);
      }
    }
  }
}

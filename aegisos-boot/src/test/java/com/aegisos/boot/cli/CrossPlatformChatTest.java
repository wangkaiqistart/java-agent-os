package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.cli.ChatCommand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 默认仅回环替身；正向验收不依赖真实Provider，也不允许以平台拒绝为成功。 */
class CrossPlatformChatTest extends CliReplWiringTest {
  @Test
  void twoMessagesAndRestartKeepOneSessionWithSuccessfulAudit() throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      configure(f);
      var output = new ByteArrayOutputStream();
      try (var runtime = f.openRuntime()) {
        assertThat(
                runtime
                    .context()
                    .getBean(ChatCommand.class)
                    .runInteractive(
                        "p",
                        null,
                        "local",
                        new ByteArrayInputStream(
                            "你好\r\n再问一句\r\n/quit\r\n".getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(output),
                        new PrintStream(new ByteArrayOutputStream())))
            .isZero();
      }
      assertThat(f.physicalSends).hasValue(2);
      assertThat(output.toString(StandardCharsets.UTF_8)).contains("安全回复");
      try (var runtime = f.openRuntime()) {
        assertThat(run(runtime.context().getBean(ChatCommand.class), "/quit\n")).isZero();
      }
      try (var c = f.reopen();
          var s = c.createStatement()) {
        try (var r =
            s.executeQuery(
                "SELECT count(*),count(distinct session_id) FROM agent_processings WHERE status='COMPLETED'")) {
          assertThat(r.getInt(1)).isEqualTo(2);
          assertThat(r.getInt(2)).isEqualTo(1);
        }
        try (var r =
            s.executeQuery(
                "SELECT count(*) FROM llm_calls WHERE status='SUCCEEDED' AND attempt_no=1")) {
          assertThat(r.getInt(1)).isEqualTo(2);
        }
        try (var r = s.executeQuery("SELECT count(*) FROM session_bindings")) {
          assertThat(r.getInt(1)).isEqualTo(1);
        }
      }
    }
  }
}

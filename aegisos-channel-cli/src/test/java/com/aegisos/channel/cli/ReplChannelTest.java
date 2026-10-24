package com.aegisos.channel.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplChannelTest {
  ReplChannel channel(int max) {
    return new ReplChannel(new DefaultSensitiveContentGuard(List.of("synthetic-repl-secret")), max);
  }

  @Test
  void readsOneBoundedUtf8LineAtATimeAndKeepsLastUnterminatedLine() {
    var in = new ByteArrayInputStream("你好\r\n第二条\nlast".getBytes(StandardCharsets.UTF_8));
    var c = channel(100);
    assertThat(c.readLine(in)).isEqualTo("你好");
    assertThat(c.readLine(in)).isEqualTo("第二条");
    assertThat(c.readLine(in)).isEqualTo("last");
    assertThat(c.readLine(in)).isNull();
  }

  @Test
  void limitIsBytesAndRejectsMalformedOrKnownSecretBeforeReturning() {
    assertThatThrownBy(
            () ->
                channel(3)
                    .readLine(new ByteArrayInputStream("你好\n".getBytes(StandardCharsets.UTF_8))))
        .hasMessage("CONTEXT_LIMIT");
    assertThatThrownBy(
            () -> channel(100).readLine(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 10})))
        .hasMessage("INVALID_INPUT");
    assertThatThrownBy(
            () ->
                channel(100)
                    .readLine(
                        new ByteArrayInputStream(
                            "synthetic-repl-secret\n".getBytes(StandardCharsets.UTF_8))))
        .hasMessage("SENSITIVE_CONTENT");
  }

  @Test
  void outputIsCheckedBeforeWritingAndBrokenOutputIsNotSuccess() {
    var result =
        new com.aegisos.core.agent.ProcessingResult(
            "safe-session",
            null,
            com.aegisos.core.agent.ProcessingStatus.COMPLETED,
            1,
            "synthetic-repl-secret",
            null,
            true);
    var buffer = new ByteArrayOutputStream();
    assertThatThrownBy(() -> channel(100).write(result, new PrintStream(buffer)))
        .hasMessage("SENSITIVE_CONTENT");
    assertThat(buffer.size()).isZero();
    var safe =
        new com.aegisos.core.agent.ProcessingResult(
            "safe-session",
            null,
            com.aegisos.core.agent.ProcessingStatus.COMPLETED,
            1,
            "safe",
            null,
            true);
    var broken =
        new PrintStream(
            new OutputStream() {
              public void write(int value) throws IOException {
                throw new IOException("synthetic");
              }
            });
    assertThatThrownBy(() -> channel(100).write(safe, broken))
        .hasMessage("PERSISTENCE_UNCONFIRMED");
  }
}

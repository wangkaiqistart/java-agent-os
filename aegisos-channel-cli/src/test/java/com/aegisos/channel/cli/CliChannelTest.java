package com.aegisos.channel.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliChannelTest {
  final CliChannel channel =
      new CliChannel(new DefaultSensitiveContentGuard(List.of("synthetic-cli-secret")), 64);

  @Test
  void readsOneBoundedStrictUtf8RequestUntilEof() throws Exception {
    assertThat(channel.read(new ByteArrayInputStream("你好\n下一行".getBytes(StandardCharsets.UTF_8))))
        .isEqualTo("你好\n下一行");
    for (byte[] input :
        List.of(
            new byte[] {(byte) 0xc0},
            new byte[65],
            " ".getBytes(),
            "synthetic-cli-secret".getBytes()))
      assertThatThrownBy(() -> channel.read(new ByteArrayInputStream(input)))
          .isInstanceOf(AgentException.class);
  }

  @Test
  void outputHasOnlySevenFieldsAndEscapesContent() throws Exception {
    var bytes = new ByteArrayOutputStream();
    channel.write(
        new ProcessingResult(
            "s",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ProcessingStatus.COMPLETED,
            1,
            "a\n\"b",
            null,
            true),
        new PrintStream(bytes));
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"sessionId\":\"s\",\"processId\":\"00000000-0000-0000-0000-000000000001\",\"status\":\"COMPLETED\",\"iterationCount\":1,\"reply\":\"a\\u000a\\\"b\",\"error\":null,\"persistenceConfirmed\":true}\n");
    assertThatThrownBy(
            () ->
                channel.write(
                    new ProcessingResult(
                        "s",
                        null,
                        ProcessingStatus.COMPLETED,
                        1,
                        "synthetic-cli-secret",
                        null,
                        true),
                    new PrintStream(bytes)))
        .isInstanceOf(AgentException.class);
  }
}

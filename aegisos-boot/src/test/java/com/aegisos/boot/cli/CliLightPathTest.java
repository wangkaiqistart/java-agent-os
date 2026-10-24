package com.aegisos.boot.cli;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.AegisOsApplication;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** root帮助不能因以--开头而进入Web或数据库装配。 */
class CliLightPathTest {
  @TempDir Path root;

  @Test
  void helpAndVersionBypassRuntime() throws Exception {
    for (String[] args :
        new String[][] {{"--help"}, {"--version"}, {"profile", "list", "--help"}}) {
      var bytes = new ByteArrayOutputStream();
      assertThat(
              AegisOsApplication.dispatch(
                  args,
                  new ByteArrayInputStream(new byte[0]),
                  new PrintStream(bytes),
                  new PrintStream(new ByteArrayOutputStream())))
          .isZero();
      assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
          .containsIgnoringCase("aegisos");
    }
    try (var files = Files.list(root)) {
      assertThat(files.count()).isZero();
    }
  }
}

package com.aegisos.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitPermissionsTest {
  @TempDir Path root;

  @Test
  void initCreatesPrivateConfigurationAndInstructionFiles() throws Exception {
    new InitCommand().run(root.toAbsolutePath());
    for (String relative :
        List.of(
            ".aegisos/config.yml",
            ".aegisos/agents/weather/profile.yml",
            ".aegisos/agents/weather/AGENT.md")) {
      Path file = root.resolve(relative);
      if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      else {
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
        assertThat(acl)
            .isNotEmpty()
            .allSatisfy(
                entry -> {
                  assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                  assertThat(entry.principal()).isEqualTo(Files.getOwner(file));
                });
      }
    }
  }
}

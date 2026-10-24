package com.aegisos.core.platform;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformFilesTest {
  @TempDir Path root;

  @Test
  void boundedReadRejectsDirectoryLinksAndReplacementAndReleasesHandles() throws Exception {
    Path dir = Files.createDirectory(root.resolve("正文 中文"));
    Path file = Files.writeString(dir.resolve("AGENT.md"), "abc");
    Path real = dir.toRealPath(), target = file.toRealPath();
    for (int i = 0; i < 50; i++) {
      assertThat(SecureFileAccess.read(real, target, 3))
          .containsExactly((byte) 'a', (byte) 'b', (byte) 'c');
      assertThatThrownBy(() -> SecureFileAccess.read(real, target, 2))
          .isInstanceOf(SecureFileAccess.TooLargeException.class);
    }
    assertThatThrownBy(() -> SecureFileAccess.read(real, real, 10))
        .isInstanceOf(java.io.IOException.class);
    Files.delete(file); // Windows未关闭句柄时此操作会失败。
    Path external = Files.writeString(root.resolve("external.md"), "outside");
    Files.createSymbolicLink(file, external);
    assertThatThrownBy(() -> SecureFileAccess.read(real, file.toAbsolutePath(), 100))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void singleLinkChecksRejectHardLinksAndSidecarAliases() throws Exception {
    Path file = Files.writeString(root.resolve("db"), "abc");
    SecureFileAccess.requireSingleLink(file);
    Path alias = Files.createLink(root.resolve("alias"), file);
    assertThatThrownBy(() -> SecureFileAccess.requireSingleLink(file))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(() -> SecureFileAccess.requireSingleLink(alias))
        .isInstanceOf(java.io.IOException.class);
    Files.delete(alias);
    SecureFileAccess.requireSingleLink(file);
    Path link = Files.createSymbolicLink(root.resolve("link"), file);
    assertThatThrownBy(() -> SecureFileAccess.requireSingleLink(link))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void newObjectsArePrivateAndExistingContentIsNeverOverwritten() throws Exception {
    Path dir = root.resolve("私有 目录"), file = dir.resolve("a.yml");
    OwnerOnlyFiles.createDirectory(dir);
    try (var out = OwnerOnlyFiles.newOutputStream(file)) {
      out.write(1);
    }
    requirePrivate(dir, true);
    requirePrivate(file, false);
    assertThatThrownBy(() -> OwnerOnlyFiles.newOutputStream(file))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(Files.readAllBytes(file)).containsExactly((byte) 1);
  }

  static void requirePrivate(Path path, boolean directory) throws Exception {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertThat(Files.getPosixFilePermissions(path))
          .isEqualTo(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    } else {
      var owner = Files.getOwner(path);
      var acl = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
      assertThat(acl).isNotEmpty();
      assertThat(acl)
          .allSatisfy(
              entry -> {
                assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
                assertThat(entry.principal()).isEqualTo(owner);
              });
    }
  }

  @Test
  void databasePathRejectsUriAdsAndSharedPaths() throws Exception {
    for (String value :
        List.of(
            "file:test.db",
            "db?mode=ro",
            "C:relative.db",
            "//server/share/db",
            "\\\\server\\share\\db",
            "test:stream"))
      assertThatThrownBy(() -> OwnerOnlyFiles.databasePath(value))
          .isInstanceOf(java.io.IOException.class);
    assertThat(OwnerOnlyFiles.databasePath(root.resolve("本地 数据.db").toString())).isAbsolute();
  }

  @Test
  void windowsNamesCannotAliasDevicesOrTrimmedFileNames() throws Exception {
    WindowsFiles.requireSafeNames("C:\\workspace 中文\\local.db");
    for (String name : List.of("db.", "db ", "NUL", "con.db", "COM1", "lpt9.log")) {
      assertThatThrownBy(() -> WindowsFiles.requireSafeNames("C:\\workspace\\" + name))
          .as("Win32别名拒绝")
          .isInstanceOf(java.io.IOException.class);
    }
  }
}

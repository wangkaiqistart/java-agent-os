package com.aegisos.core.platform;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/** 创建时提供受保护DACL，阻止宽松父ACL被继承；不先创建公开文件再补权限。 */
final class WindowsPrivateFiles {
  private WindowsPrivateFiles() {}

  interface Advapi extends StdCallLibrary {
    boolean OpenProcessToken(Pointer process, int access, PointerByReference token);

    boolean GetTokenInformation(
        Pointer token, int type, Pointer buffer, int size, IntByReference required);

    boolean ConvertSidToStringSidW(Pointer sid, PointerByReference text);

    boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(
        WString text, int revision, PointerByReference descriptor, IntByReference size);
  }

  interface Kernel extends StdCallLibrary {
    Pointer GetCurrentProcess();

    boolean CloseHandle(Pointer handle);

    Pointer LocalFree(Pointer memory);

    int GetLastError();

    boolean CreateDirectoryW(WString path, Pointer attributes);

    Pointer CreateFileW(
        WString path,
        int access,
        int share,
        Pointer security,
        int disposition,
        int flags,
        Pointer template);

    boolean WriteFile(
        Pointer handle, byte[] bytes, int length, IntByReference written, Pointer overlapped);
  }

  private static final class Api {
    static final Advapi SECURITY = Native.load("advapi32", Advapi.class);
    static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
  }

  private static String currentSid() throws IOException {
    var token = new PointerByReference();
    if (!Api.SECURITY.OpenProcessToken(Api.KERNEL.GetCurrentProcess(), 8, token))
      throw SecureFileAccess.unavailable();
    try {
      var size = new IntByReference();
      Api.SECURITY.GetTokenInformation(token.getValue(), 1, null, 0, size);
      if (size.getValue() <= 0 || size.getValue() > 65536) throw SecureFileAccess.unavailable();
      try (var buffer = new Memory(size.getValue())) {
        if (!Api.SECURITY.GetTokenInformation(
            token.getValue(), 1, buffer, (int) buffer.size(), size))
          throw SecureFileAccess.unavailable();
        var text = new PointerByReference();
        if (!Api.SECURITY.ConvertSidToStringSidW(buffer.getPointer(0), text))
          throw SecureFileAccess.unavailable();
        try {
          return text.getValue().getWideString(0);
        } finally {
          Api.KERNEL.LocalFree(text.getValue());
        }
      }
    } finally {
      Api.KERNEL.CloseHandle(token.getValue());
    }
  }

  private static Pointer create(Path path, boolean directory) throws IOException {
    if (Native.POINTER_SIZE != 8) throw SecureFileAccess.unavailable();
    Path absolute = path.toAbsolutePath().normalize();
    WindowsFiles.localVolume(absolute);
    String sid = currentSid();
    var descriptor = new PointerByReference();
    // D:P 是受保护DACL：不能让CreateFile自动并入父目录Everyone/Users授权。
    String sddl = "O:" + sid + "D:P(A;" + (directory ? "OICI" : "") + ";FA;;;" + sid + ")";
    if (!Api.SECURITY.ConvertStringSecurityDescriptorToSecurityDescriptorW(
        new WString(sddl), 1, descriptor, null)) throw SecureFileAccess.unavailable();
    try (var attributes = new Memory(24)) {
      attributes.clear();
      attributes.setInt(0, 24);
      attributes.setPointer(8, descriptor.getValue());
      WString name = new WString("\\\\?\\" + absolute);
      if (directory) {
        if (!Api.KERNEL.CreateDirectoryW(name, attributes)) failCreate();
        return null;
      }
      Pointer handle = Api.KERNEL.CreateFileW(name, 0x40000000, 0, attributes, 1, 0x00200080, null);
      if (handle == null || Pointer.nativeValue(handle) == -1) failCreate();
      return handle;
    } finally {
      Api.KERNEL.LocalFree(descriptor.getValue());
    }
  }

  private static void failCreate() throws IOException {
    int error = Api.KERNEL.GetLastError();
    if (error == 80 || error == 183) throw new FileAlreadyExistsException("<private-object>");
    throw SecureFileAccess.unavailable();
  }

  static void createDirectory(Path path) throws IOException {
    create(path, true);
  }

  static OutputStream newOutputStream(Path file) throws IOException {
    Pointer handle = create(file, false);
    return new OutputStream() {
      private boolean closed;

      public void write(int b) throws IOException {
        write(new byte[] {(byte) b});
      }

      public void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (closed) throw SecureFileAccess.unavailable();
        while (length > 0) {
          byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + Math.min(length, 8192));
          var n = new IntByReference();
          if (!Api.KERNEL.WriteFile(handle, chunk, chunk.length, n, null)
              || n.getValue() <= 0
              || n.getValue() > chunk.length) throw SecureFileAccess.unavailable();
          offset += n.getValue();
          length -= n.getValue();
        }
      }

      public void close() throws IOException {
        if (!closed) {
          closed = true;
          if (!Api.KERNEL.CloseHandle(handle)) throw SecureFileAccess.unavailable();
        }
      }
    };
  }
}

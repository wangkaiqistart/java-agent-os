package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.*;

class ProviderSnapshotTest {
  @TempDir Path dir;

  @Test
  void mutableFileAndCredentialSourceOnlyChangeAfterRestart() throws Exception {
    var credential = new AtomicReference<>(UUID.randomUUID().toString());
    String oldKey = credential.get();
    var names = new ArrayList<String>();
    var resolver =
        new EnvironmentCredentialResolver(
            n -> {
              names.add(n);
              return credential.get();
            });
    Path file = dir.resolve("providers.yml");
    try (var a =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()));
        var b =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()))) {
      write(file, a, "p", "m", "OLD_KEY");
      try (var c = open(file, resolver)) {
        var service = c.getBean(ProviderService.class);
        service.chat(ModelRequest.text("p", "m", "x"));
        write(file, b, "q", "m2", "NEW_KEY");
        credential.set(UUID.randomUUID().toString());
        c.getEnvironment()
            .getPropertySources()
            .replace(
                "file",
                new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("file", new org.springframework.core.io.FileSystemResource(file))
                    .getFirst());
        var bound = c.getBean(ProviderProperties.class).getProviders().getFirst();
        bound.setName("q");
        bound.setModels(List.of("m2"));
        bound.setBaseUrl(b.uri().toString());
        bound.setCredentialRef("env:NEW_KEY");
        service.chat(ModelRequest.text("p", "m", "x"));
        assertThatThrownBy(() -> service.chat(ModelRequest.text("q", "m2", "x")))
            .isInstanceOf(ProviderException.class);
        assertThat(a.calls).hasValue(2);
        assertThat(b.calls).hasValue(0);
        assertThat(a.auth.stream().allMatch(v -> v.equals("Bearer " + oldKey))).isTrue();
        assertThat(names).containsExactly("OLD_KEY");
      }
      try (var c = open(file, resolver)) {
        c.getBean(ProviderService.class).chat(ModelRequest.text("q", "m2", "x"));
        assertThat(b.calls).hasValue(1);
        assertThat(b.auth.stream().allMatch(v -> v.equals("Bearer " + credential.get()))).isTrue();
        assertThat(names).containsExactly("OLD_KEY", "NEW_KEY");
      }
    }
  }

  void write(Path file, BootProviderFixture stub, String name, String model, String reference)
      throws Exception {
    Files.writeString(
        file,
        "aegisos:\n  providers:\n    - name: "
            + name
            + "\n      adapter: openai-compatible\n      models: ["
            + model
            + "]\n      base-url: "
            + stub.uri()
            + "\n      credential-ref: env:"
            + reference
            + "\n");
  }

  AnnotationConfigApplicationContext open(Path file, EnvironmentCredentialResolver resolver)
      throws Exception {
    var c = new AnnotationConfigApplicationContext();
    c.getEnvironment().setActiveProfiles("test");
    c.getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "workspace", Map.of("aegisos.workspace-root", file.getParent().toString())));
    c.getEnvironment()
        .getPropertySources()
        .addFirst(
            new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("file", new org.springframework.core.io.FileSystemResource(file))
                .getFirst());
    c.registerBean(EnvironmentCredentialResolver.class, () -> resolver);
    c.registerBean(
        LlmCallAuditStore.class,
        () ->
            new LlmCallAuditStore() {
              public AuditHandle begin(AuditStart s) {
                return new AuditHandle(UUID.randomUUID());
              }

              public void complete(AuditHandle h, AuditCompletion cc) {}

              public void reject(AuditRejection r) {}
            });
    c.register(ProviderConfiguration.class);
    c.refresh();
    return c;
  }
}

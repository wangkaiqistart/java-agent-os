package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 此夹具只替代 Memory 端口；生产 PromptBuilder 与 ContextLoader 均为真实实现。 */
class MinimalPromptTest {
  @TempDir Path root;
  final UUID processId = UUID.randomUUID();
  final ProcessingHandle handle =
      new ProcessingHandle(
          processId, UUID.randomUUID().toString(), "logical", UUID.randomUUID(), 0);
  final ContextBudget budget = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);

  PromptBuilder builder(String user) throws Exception {
    Path directory = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(directory.resolve("AGENT.md"), "任务指令");
    var current =
        new ExchangeGroup(
            UUID.randomUUID(),
            processId,
            0,
            ExchangeGroup.Kind.USER,
            ExchangeGroup.State.CLOSED,
            null,
            0,
            0,
            List.of(new ModelMessage(ModelMessage.Role.USER, user, null, List.of())));
    var memory =
        (MemoryService)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {MemoryService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("loadContextHistory"))
                    return new ContextHistory(List.of(), List.of(current));
                  if (method.getName().equals("recallLongTerm")) return Optional.empty();
                  throw new AssertionError("Unexpected Memory operation: " + method.getName());
                });
    var guard = new DefaultSensitiveContentGuard(List.of("synthetic-prompt-secret-123"));
    ToolRegistryView tools =
        new ToolRegistryView() {
          public Optional<AegisTool> find(String name) {
            return Optional.empty();
          }

          public List<ToolSchema> schemas() {
            return List.of();
          }
        };
    var sources =
        new AgentSourceRegistry(
            List.of(
                new AgentSource(
                    "logical",
                    root.toRealPath(),
                    directory.toRealPath(),
                    root.relativize(directory))));
    return new PromptBuilder(
        sources,
        new ContextLoader(budget, guard),
        memory,
        tools,
        budget,
        guard,
        Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC),
        ZoneId.of("Asia/Shanghai"));
  }

  Profile profile() {
    return new Profile(
        "logical",
        null,
        new Identity("助手", "角色说明"),
        new ProviderSelection("p", "m", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  boolean supported() throws Exception {
    try (var directory = Files.newDirectoryStream(root)) {
      boolean supported = directory instanceof SecureDirectoryStream<?>;
      if (Boolean.getBoolean("aegisos.test.require-secure-directory-stream"))
        assertThat(supported).isTrue();
      Path probe = Files.createTempFile(root, "platform-probe", ".md");
      try {
        Files.writeString(probe, "平台安全读取探针");
        byte[] bytes =
            com.aegisos.core.platform.SecureFileAccess.read(
                root.toRealPath(), probe.toRealPath(), 128);
        if (!new String(bytes, java.nio.charset.StandardCharsets.UTF_8).equals("平台安全读取探针"))
          throw new AssertionError("PLATFORM_READ_REQUIRED");
        return true;
      } finally {
        Files.delete(probe);
      }
    }
  }

  @Test
  void requiredRoleInstructionsTimeAndCurrentUserHaveStableOrder() throws Exception {
    var builder = builder("用户请求");
    if (!supported()) {
      assertThatThrownBy(() -> builder.build(profile(), handle, 20))
          .hasMessage("CONTEXT_FILE_UNAVAILABLE");
      return;
    }
    var prompt = builder.build(profile(), handle, 20).prompt();
    assertThat(prompt.messages()).hasSize(2);
    assertThat(prompt.messages().getFirst().content())
        .containsSubsequence("角色说明", "任务指令", "2026-10-22T11:00", "Asia/Shanghai");
    assertThat(prompt.messages().get(1).role()).isEqualTo(ModelMessage.Role.USER);
    assertThat(prompt.messages().get(1).content()).isEqualTo("用户请求");
    assertThat(prompt.maxOutputTokens()).isEqualTo(1024);
    assertThat(prompt.toolSchemas()).isEmpty();
  }

  @Test
  void recoveredSensitiveMessageIsRejectedBeforeSending() throws Exception {
    var builder = builder("synthetic-prompt-secret-123");
    assertThatThrownBy(() -> builder.build(profile(), handle, 20))
        .hasMessage(supported() ? "SENSITIVE_CONTENT" : "CONTEXT_FILE_UNAVAILABLE");
  }

  @Test
  void mandatoryCurrentRequestCannotBeClipped() throws Exception {
    var builder = builder("中".repeat(12000));
    assertThatThrownBy(() -> builder.build(profile(), handle, 20))
        .hasMessage(supported() ? "CONTEXT_LIMIT" : "CONTEXT_FILE_UNAVAILABLE");
  }
}

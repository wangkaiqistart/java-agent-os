package com.aegisos.core.agent;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.support.ReactTestFixtures;
import com.aegisos.core.tool.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AgentServiceTest {
  @TempDir Path root;
  final ContextBudget budget = new ContextBudget(262144, 32768, 32768, 32, 8192, 16384, 8, 1024);
  final SensitiveContentGuard guard =
      new DefaultSensitiveContentGuard(List.of(ReactTestFixtures.SYNTHETIC_SECRET));
  final List<String> operations = new ArrayList<>();
  final AtomicReference<String> input = new AtomicReference<>();
  final String session = UUID.randomUUID().toString();
  final UUID process = UUID.randomUUID();
  final UUID runtime = UUID.randomUUID();
  final AtomicInteger responses = new AtomicInteger();
  com.aegisos.core.tool.ToolExecutor executor =
      (c, t) -> {
        throw new AssertionError("NO_TOOL_EXPECTED");
      };
  final AtomicBoolean finishFails = new AtomicBoolean();

  Profile profile() {
    return new Profile(
        "p",
        null,
        null,
        new ProviderSelection("provider", "model", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  MemoryService memory() {
    return (MemoryService)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {MemoryService.class},
            (proxy, method, args) -> {
              operations.add(method.getName());
              return switch (method.getName()) {
                case "createSession" ->
                    new SessionSnapshot(session, "p", Instant.EPOCH, Instant.EPOCH, 0, List.of());
                case "loadSession" -> {
                  if (!session.equals(args[0]))
                    throw new AgentException(AgentErrorCode.UNKNOWN_SESSION);
                  if (!"p".equals(args[1]))
                    throw new AgentException(AgentErrorCode.SESSION_PROFILE_MISMATCH);
                  yield new SessionSnapshot(
                      session, "p", Instant.EPOCH, Instant.EPOCH, 0, List.of());
                }
                case "beginProcessing" -> {
                  input.set((String) args[2]);
                  yield new ProcessingHandle(process, session, "p", runtime, 0);
                }
                case "loadContextHistory" ->
                    new ContextHistory(
                        List.of(),
                        List.of(
                            new ExchangeGroup(
                                UUID.randomUUID(),
                                process,
                                0,
                                ExchangeGroup.Kind.USER,
                                ExchangeGroup.State.CLOSED,
                                null,
                                0,
                                0,
                                List.of(
                                    new ModelMessage(
                                        ModelMessage.Role.USER, input.get(), null, List.of())))));
                case "prepareModelTurn", "commitToolObservation", "recordEvent" -> args[0];
                case "acceptModelResponse" ->
                    new SessionStore.Acceptance(
                        (ProcessingHandle) args[0], UUID.randomUUID(), responses.incrementAndGet());
                case "finish" -> {
                  if (finishFails.get())
                    throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
                  yield new ProcessingResult(
                      session,
                      process,
                      (ProcessingStatus) args[1],
                      responses.get(),
                      (String) args[3],
                      (AgentErrorCode) args[2],
                      true);
                }
                case "recallLongTerm", "findCommittedObservation" -> Optional.empty();
                default -> throw new AssertionError(method.getName());
              };
            });
  }

  AgentService service(ReactTestFixtures.SequenceProvider provider) throws Exception {
    Path directory = Files.createDirectories(root.resolve(".aegisos/agents/directory"));
    Files.writeString(directory.resolve("AGENT.md"), "任务指令");
    var sources =
        new AgentSourceRegistry(
            List.of(
                new AgentSource(
                    "p", root.toRealPath(), directory.toRealPath(), root.relativize(directory))));
    var memory = memory();
    ToolRegistryView registry =
        new ToolRegistryView() {
          public Optional<AegisTool> find(String n) {
            return Optional.empty();
          }

          public List<ToolSchema> schemas() {
            return List.of();
          }
        };
    var prompt =
        new PromptBuilder(
            sources,
            new ContextLoader(budget, guard),
            memory,
            registry,
            budget,
            guard,
            ReactTestFixtures.CLOCK,
            ZoneOffset.UTC);
    var loop = new ReActLoop(provider, memory, prompt, executor, budget, guard);
    return new AgentService(new ProfileRegistry(List.of(profile())), memory, loop, guard, runtime);
  }

  boolean supported() throws Exception {
    try (var stream = Files.newDirectoryStream(root)) {
      boolean yes = stream instanceof SecureDirectoryStream<?>;
      if (Boolean.getBoolean("aegisos.test.require-secure-directory-stream"))
        assertThat(yes).isTrue();
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
  void runtimeCreatesUuidSessionAndRejectsUnknownProfile() throws Exception {
    var service = service(new ReactTestFixtures.SequenceProvider());
    assertThat(service.createSession("p").sessionId()).isEqualTo(session);
    assertThatThrownBy(() -> service.createSession("missing")).hasMessage("UNKNOWN_PROFILE");
  }

  @Test
  void oneTextResponseCompletesOnlyAfterCommitAndScopeIsCleared() throws Exception {
    var provider = new ReactTestFixtures.SequenceProvider(ReactTestFixtures.text("安全回复"));
    var service = service(provider);
    var result = service.process(new AgentRequest(session, "p", "请求"));
    if (supported()) {
      assertThat(result.status()).isEqualTo(ProcessingStatus.COMPLETED);
      assertThat(result.reply()).isEqualTo("安全回复");
      assertThat(result.iterationCount()).isEqualTo(1);
      assertThat(provider.logicalRequests).hasValue(1);
      assertThat(operations)
          .containsSubsequence(
              "beginProcessing",
              "loadContextHistory",
              "prepareModelTurn",
              "acceptModelResponse",
              "finish");
    } else {
      assertThat(result.errorCode()).isEqualTo(AgentErrorCode.CONTEXT_FILE_UNAVAILABLE);
      assertThat(provider.logicalRequests).hasValue(0);
    }
    assertThat(ProfileContext.current()).isNull();
  }

  @Test
  void invalidSessionAndSensitiveInputNeverReachProvider() throws Exception {
    var provider = new ReactTestFixtures.SequenceProvider();
    var service = service(provider);
    assertThat(service.process(new AgentRequest("unknown", "p", "safe")).errorCode())
        .isEqualTo(AgentErrorCode.UNKNOWN_SESSION);
    assertThat(
            service
                .process(new AgentRequest(session, "p", ReactTestFixtures.SYNTHETIC_SECRET))
                .errorCode())
        .isEqualTo(AgentErrorCode.SENSITIVE_CONTENT);
    assertThat(provider.logicalRequests).hasValue(0);
    assertThat(operations).doesNotContain("beginProcessing");
    assertThat(ProfileContext.current()).isNull();
  }

  @Test
  void emptyResponseFailsAndFinalCommitFailureCannotReturnReply() throws Exception {
    var provider = new ReactTestFixtures.SequenceProvider(ReactTestFixtures.text("  "));
    var service = service(provider);
    var result = service.process(new AgentRequest(session, "p", "safe"));
    assertThat(result.status()).isEqualTo(ProcessingStatus.FAILED);
    if (supported()) assertThat(result.errorCode()).isEqualTo(AgentErrorCode.INVALID_RESPONSE);
    finishFails.set(true);
    provider = new ReactTestFixtures.SequenceProvider(ReactTestFixtures.text("reply"));
    service = service(provider);
    result = service.process(new AgentRequest(session, "p", "safe"));
    assertThat(result.persistenceConfirmed()).isFalse();
    assertThat(result.reply()).isNull();
    assertThat(result.errorCode()).isEqualTo(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
    assertThat(ProfileContext.current()).isNull();
  }
}

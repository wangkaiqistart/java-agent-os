package com.aegisos.core.support;

import com.aegisos.core.provider.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 确定性响应序列；不读取环境，不将逻辑请求和网络 attempt 混为一谈。 */
public final class ReactTestFixtures {
  public static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);
  public static final String SYNTHETIC_SECRET = "synthetic-secret-react-only-123456";

  private ReactTestFixtures() {}

  public static ModelResponse text(String content) {
    return new ModelResponse(
        UUID.randomUUID(),
        "test-provider",
        "test-model",
        content,
        List.of(),
        "stop",
        new TokenUsage(1L, 1L, 2L));
  }

  public static final class SequenceProvider implements ProviderService, ProfileProviderService {
    private final Deque<ModelResponse> responses;
    public final AtomicInteger logicalRequests = new AtomicInteger();
    public final AtomicInteger acceptedResponses = new AtomicInteger();
    public final AtomicInteger physicalAttempts = new AtomicInteger();
    public final List<ProfilePrompt> prompts = new ArrayList<>();

    public SequenceProvider(ModelResponse... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    private ModelResponse next() {
      logicalRequests.incrementAndGet();
      physicalAttempts.incrementAndGet();
      if (responses.isEmpty()) throw new IllegalStateException("FIXTURE_EXHAUSTED");
      return responses.removeFirst();
    }

    public ModelResponse chat(ModelRequest request) {
      return next();
    }

    public ModelResponse chat(String session, ModelRequest request) {
      return next();
    }

    public ModelResponse chatProfile(String session, String profile, ProfilePrompt prompt) {
      prompts.add(prompt);
      return next();
    }

    @Override
    public ModelResponse chatProfile(
        String session, String profile, ProfilePrompt prompt, ProviderInvocation invocation) {
      if (invocation == null || invocation.processId() == null || invocation.iterationNo() < 1)
        throw new AssertionError("MISSING_INVOCATION");
      return chatProfile(session, profile, prompt);
    }
  }
}

package com.aegisos.provider.support;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.*;
import com.aegisos.provider.config.*;
import com.aegisos.provider.service.*;
import java.time.*;
import java.util.*;

public final class ProviderFixtures {
  private ProviderFixtures() {}

  public static ProviderDefinition definition(HttpStub stub, int retries) {
    return definition(stub, retries, Duration.ofMillis(120));
  }

  /** 路由/容量测试显式给足调度余量；超时与重试测试仍保留原120ms故障边界。 */
  public static ProviderDefinition definition(HttpStub stub, int retries, Duration readTimeout) {
    return new ProviderDefinition(
        "p",
        "openai-compatible",
        Set.of("m"),
        stub.uri(),
        "env:TEST_KEY",
        retries,
        Duration.ofSeconds(1),
        readTimeout);
  }

  public static ProviderRegistration registration(HttpStub stub, int retries) {
    var d = definition(stub, retries);
    return new ProviderRegistration(
        d, new OpenAiClientFactory().create(d, UUID.randomUUID().toString()), null);
  }

  public static class Audit implements LlmCallAuditStore {
    public final List<AuditStart> starts = new ArrayList<>();
    public final List<AuditCompletion> completed = new ArrayList<>();
    public final List<AuditRejection> rejected = new ArrayList<>();

    public AuditHandle begin(AuditStart s) {
      starts.add(s);
      return new AuditHandle(UUID.randomUUID());
    }

    public void complete(AuditHandle h, AuditCompletion c) {
      completed.add(c);
    }

    public void reject(AuditRejection r) {
      rejected.add(r);
    }
  }

  public static DefaultProviderService service(HttpStub stub, int retries, Audit audit) {
    return new DefaultProviderService(List.of(registration(stub, retries)), audit, () -> {});
  }
}

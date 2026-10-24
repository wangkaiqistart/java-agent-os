package com.aegisos.provider.service;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import java.time.Instant;
import java.util.UUID;

/** 审计白名单不接收正文、Tool schema/参数、端点、凭证或原始异常。 */
public final class AuditMetadataPolicy {
  private AuditMetadataPolicy() {}

  public record Identity(String providerName, String modelName) {}

  public static Identity identity(
      ModelRequest r, com.aegisos.provider.config.ProviderRegistry registry) {
    String p = SafeProviderMetadata.provider(r == null ? null : r.providerName());
    var registration = p == null ? null : registry.get(p);
    if (registration == null) return new Identity(null, null);
    String m = SafeProviderMetadata.model(r.modelName());
    return new Identity(p, m != null && registration.definition().models().contains(m) ? m : null);
  }

  public static AuditStart start(UUID id, long attempt, Identity identity) {
    return start(id, attempt, identity, null);
  }

  public static AuditStart start(UUID id, long attempt, Identity identity, String session) {
    return start(id, attempt, identity, session, null);
  }

  public static AuditStart start(
      UUID id, long attempt, Identity identity, String session, ProviderInvocation invocation) {
    return new AuditStart(
        id,
        attempt,
        identity.providerName(),
        identity.modelName(),
        Instant.now(),
        SessionAssociation.safe(session),
        invocation);
  }

  public static AuditRejection rejection(UUID id, Identity identity, ProviderErrorCode code) {
    return rejection(id, identity, code, null);
  }

  public static AuditRejection rejection(
      UUID id, Identity identity, ProviderErrorCode code, String session) {
    return rejection(id, identity, code, session, null);
  }

  public static AuditRejection rejection(
      UUID id,
      Identity identity,
      ProviderErrorCode code,
      String session,
      ProviderInvocation invocation) {
    return new AuditRejection(
        id,
        identity.providerName(),
        identity.modelName(),
        Instant.now(),
        code,
        SessionAssociation.safe(session),
        invocation);
  }
}

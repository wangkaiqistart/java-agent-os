package com.aegisos.provider.service;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.OpenAiMessageMapper;
import com.aegisos.provider.config.ProviderRegistration;
import com.aegisos.provider.config.ProviderRegistry;
import java.time.Instant;
import java.util.*;

/** 唯一审计/重试边界；任何网络调用都在数据库短事务之外。 */
public final class DefaultProviderService implements ProviderService, ProfileProviderService {
  private final ProviderRegistry registrations;
  private final LlmCallAuditStore audit;
  private final RetryWaiter waiter;
  private final com.aegisos.core.profile.ProfileRegistry profiles;

  public DefaultProviderService(
      Collection<ProviderRegistration> registrations, LlmCallAuditStore audit, RetryWaiter waiter) {
    this(new ProviderRegistry(registrations), audit, waiter);
  }

  public DefaultProviderService(
      ProviderRegistry registrations, LlmCallAuditStore audit, RetryWaiter waiter) {
    this(registrations, audit, waiter, new com.aegisos.core.profile.ProfileRegistry(List.of()));
  }

  /** Profile 只是只读路由快照；所有入口共用本类的审计/重试循环。 */
  public DefaultProviderService(
      ProviderRegistry registrations,
      LlmCallAuditStore audit,
      RetryWaiter waiter,
      com.aegisos.core.profile.ProfileRegistry profiles) {
    this.profiles = Objects.requireNonNull(profiles);
    this.registrations = Objects.requireNonNull(registrations);
    this.audit = Objects.requireNonNull(audit);
    this.waiter = Objects.requireNonNull(waiter);
  }

  public ModelResponse chat(ModelRequest request) {
    return execute(UUID.randomUUID(), SessionAssociation.Mode.LEGACY_UNASSOCIATED, null, request);
  }

  /** 显式新入口，校验先于外呼；合法追踪值并不要求会话已存在。 */
  public ModelResponse chat(String sessionId, ModelRequest request) {
    return execute(UUID.randomUUID(), SessionAssociation.Mode.SESSION_REQUIRED, sessionId, request);
  }

  /** Profile查找必须在新UUID产生后；所有转换错误经同一个拒绝审计，不在外层抛未审计异常。 */
  public ModelResponse chatProfile(String sessionId, String profileName, ProfilePrompt prompt) {
    UUID id = UUID.randomUUID();
    String session = SessionAssociation.safe(sessionId);
    var associationError =
        SessionAssociation.error(SessionAssociation.Mode.SESSION_REQUIRED, sessionId);
    if (associationError != null) return reject(id, null, null, null, associationError);
    if (profileName == null || profileName.isBlank())
      return reject(id, session, null, null, ProviderErrorCode.PROFILE_REQUIRED);
    var profile = profiles.get(profileName);
    if (profile == null) return reject(id, session, null, null, ProviderErrorCode.UNKNOWN_PROFILE);
    var selection = profile.provider();
    var identity =
        AuditMetadataPolicy.identity(
            new ModelRequest(selection.name(), selection.model(), null, null, null), registrations);
    if (prompt == null)
      return reject(
          id,
          session,
          identity.providerName(),
          identity.modelName(),
          ProviderErrorCode.INVALID_REQUEST);
    for (var schema : prompt.toolSchemas())
      if (schema == null || !profile.tools().contains(schema.name()))
        return reject(
            id,
            session,
            identity.providerName(),
            identity.modelName(),
            ProviderErrorCode.INVALID_REQUEST);
    return execute(
        id,
        SessionAssociation.Mode.SESSION_REQUIRED,
        sessionId,
        new ModelRequest(
            selection.name(),
            selection.model(),
            prompt.messages(),
            prompt.toolSchemas(),
            new GenerationOptions(selection.temperature(), prompt.maxOutputTokens())));
  }

  private ModelResponse execute(
      UUID id, SessionAssociation.Mode mode, String rawSession, ModelRequest request) {
    String session =
        mode == SessionAssociation.Mode.LEGACY_UNASSOCIATED
            ? null
            : SessionAssociation.safe(rawSession);
    var identity = AuditMetadataPolicy.identity(request, registrations);
    String p = identity.providerName();
    String m = identity.modelName();
    var associationError = SessionAssociation.error(mode, rawSession);
    if (associationError != null) return reject(id, session, p, m, associationError);
    ProviderRegistration selected;
    try {
      selected = validate(request);
    } catch (ProviderException e) {
      return reject(id, session, p, m, e.failure().code());
    }
    // begin 返回意味着独立事务已经提交；HTTP不能位于这个事务中。
    // complete也必须提交才可返回或重试，失败时保留UNKNOWN，不能隐藏此前外呼。
    long attempts = 0;
    ExternalOutcome outcome = ExternalOutcome.NOT_SENT;
    for (long number = 1; number <= (long) selected.definition().maxRetries() + 1; number++) {
      AuditHandle handle;
      try {
        handle = audit.begin(AuditMetadataPolicy.start(id, number, identity, session));
      } catch (RuntimeException e) {
        throw failure(id, ProviderErrorCode.AUDIT_UNAVAILABLE, p, m, attempts, false, outcome);
      }
      attempts++;
      long start = System.nanoTime();
      ModelResponse result = null;
      ProviderFailure failed = null;
      try {
        result = selected.adapter().chat(request);
        if (result == null
            || ((result.content() == null || result.content().isEmpty())
                && result.toolCalls().isEmpty()))
          throw ProviderErrorClassifier.error(
              ProviderErrorCode.PROVIDER_PROTOCOL_ERROR, false, ExternalOutcome.RESPONDED);
        outcome = merge(outcome, ExternalOutcome.RESPONDED);
      } catch (RuntimeException e) {
        failed = ProviderErrorClassifier.classify(e, false).failure();
        outcome = merge(outcome, failed.externalOutcome());
      }
      long duration = Math.max(0, (System.nanoTime() - start) / 1_000_000);
      try {
        audit.complete(
            handle,
            new AuditCompletion(
                failed == null ? AuditStatus.SUCCEEDED : AuditStatus.FAILED,
                Instant.now(),
                duration,
                failed == null ? result.usage() : null,
                failed == null ? null : failed.code(),
                failed != null && failed.retryable()));
      } catch (RuntimeException e) {
        throw failure(
            id,
            ProviderErrorCode.AUDIT_UNAVAILABLE,
            p,
            m,
            attempts,
            false,
            ExternalOutcome.UNKNOWN);
      }
      if (failed == null)
        return new ModelResponse(
            id, p, m, result.content(), result.toolCalls(), result.finishReason(), result.usage());
      if (!failed.retryable() || number >= (long) selected.definition().maxRetries() + 1)
        throw failure(id, failed.code(), p, m, attempts, failed.retryable(), outcome);
      try {
        waiter.pause();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw failure(id, failed.code(), p, m, attempts, false, outcome);
      }
    }
    throw new IllegalStateException("Invalid retry budget");
  }

  private ProviderRegistration validate(ModelRequest r) {
    if (r == null || r.providerName() == null || r.providerName().isBlank())
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.PROVIDER_REQUIRED, false, ExternalOutcome.NOT_SENT);
    if (SafeProviderMetadata.provider(r.providerName()) == null)
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.INVALID_REQUEST, false, ExternalOutcome.NOT_SENT);
    if (r.modelName() == null || r.modelName().isBlank())
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.MODEL_REQUIRED, false, ExternalOutcome.NOT_SENT);
    if (SafeProviderMetadata.model(r.modelName()) == null)
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.INVALID_REQUEST, false, ExternalOutcome.NOT_SENT);
    var registration = registrations.get(r.providerName());
    if (registration == null)
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.UNKNOWN_PROVIDER, false, ExternalOutcome.NOT_SENT);
    if (!registration.available())
      throw ProviderErrorClassifier.error(
          registration.unavailableReason(), false, ExternalOutcome.NOT_SENT);
    if (!registration.definition().models().contains(r.modelName()))
      throw ProviderErrorClassifier.error(
          ProviderErrorCode.MODEL_UNAVAILABLE, false, ExternalOutcome.NOT_SENT);
    OpenAiMessageMapper.validate(r);
    return registration;
  }

  private ModelResponse reject(
      UUID id, String session, String p, String m, ProviderErrorCode code) {
    try {
      audit.reject(
          AuditMetadataPolicy.rejection(id, new AuditMetadataPolicy.Identity(p, m), code, session));
    } catch (RuntimeException e) {
      throw failure(
          id, ProviderErrorCode.AUDIT_UNAVAILABLE, p, m, 0, false, ExternalOutcome.NOT_SENT);
    }
    throw failure(id, code, p, m, 0, false, ExternalOutcome.NOT_SENT);
  }

  private static ExternalOutcome merge(ExternalOutcome a, ExternalOutcome b) {
    return a == ExternalOutcome.UNKNOWN || b == ExternalOutcome.UNKNOWN
        ? ExternalOutcome.UNKNOWN
        : a == ExternalOutcome.RESPONDED || b == ExternalOutcome.RESPONDED
            ? ExternalOutcome.RESPONDED
            : ExternalOutcome.NOT_SENT;
  }

  private static ProviderException failure(
      UUID id,
      ProviderErrorCode code,
      String p,
      String m,
      long attempts,
      boolean retryable,
      ExternalOutcome outcome) {
    return new ProviderException(new ProviderFailure(id, code, p, m, attempts, retryable, outcome));
  }
}

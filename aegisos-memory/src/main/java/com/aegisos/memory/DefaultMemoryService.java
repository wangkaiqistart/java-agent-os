package com.aegisos.memory;

import com.aegisos.core.agent.*;
import com.aegisos.core.context.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import java.util.*;

/** 唯一记忆门面：写入前检查、恢复后检查；只返回后端已提交视图，不把 save 当作 commit。 */
public final class DefaultMemoryService implements MemoryService {
  private final SessionStore store;
  private final LongTermMemoryStore longTerm;
  private final SensitiveContentGuard guard;
  private final ContextBudget budget;

  public DefaultMemoryService(
      SessionStore store,
      LongTermMemoryStore longTerm,
      SensitiveContentGuard guard,
      ContextBudget budget) {
    this.store = Objects.requireNonNull(store);
    this.longTerm = Objects.requireNonNull(longTerm);
    this.guard = Objects.requireNonNull(guard);
    this.budget = Objects.requireNonNull(budget);
  }

  /** 新身份入口仍走唯一门面，不能因getOrCreate绕过已知凭据或恢复历史检查。 */
  @Override
  public SessionSnapshot getOrCreateSession(SessionIdentity identity) {
    Objects.requireNonNull(identity);
    guard.check(identity.channel());
    guard.check(identity.user());
    guard.check(identity.profile());
    var saved = store.getOrCreateSession(identity);
    guard.check(saved.sessionId());
    guard.check(saved.profileName());
    for (var message : saved.messages()) checkRestored(message);
    return saved;
  }

  public SessionSnapshot createSession(String profile) {
    guard.check(profile);
    return store.createSession(profile);
  }

  public SessionSnapshot loadSession(String session, String profile) {
    guard.check(session);
    guard.check(profile);
    var result = store.loadSession(session, profile);
    for (var message : result.messages()) checkRestored(message);
    return result;
  }

  public ProcessingHandle beginProcessing(
      String session, String profile, String user, UUID runtime) {
    guard.check(session);
    guard.check(profile);
    guard.check(user);
    if (user == null || user.isBlank()) throw new AgentException(AgentErrorCode.INVALID_INPUT);
    check(new ModelMessage(ModelMessage.Role.USER, user, null, List.of()));
    return store.beginProcessing(session, profile, user, runtime);
  }

  public ContextHistory loadContextHistory(ProcessingHandle handle, int max) {
    var history = store.loadContextHistory(handle, max);
    for (var processing : history.completedProcessings())
      for (var group : processing) for (var message : group.messages()) checkRestored(message);
    for (var group : history.currentGroups())
      for (var message : group.messages()) checkRestored(message);
    return history;
  }

  public ProcessingHandle prepareModelTurn(ProcessingHandle handle, int iteration) {
    return store.prepareModelTurn(handle, iteration);
  }

  public Acceptance acceptModelResponse(
      ProcessingHandle handle, int iteration, ModelResponse response) {
    if (response == null || response.requestId() == null)
      throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
    guard.check(response.providerName());
    guard.check(response.modelName());
    guard.check(response.finishReason());
    check(
        new ModelMessage(
            ModelMessage.Role.ASSISTANT, response.content(), null, response.toolCalls()));
    return store.acceptModelResponse(handle, iteration, response);
  }

  private void checkRestored(ModelMessage message) {
    if (message.role() == ModelMessage.Role.TOOL) guard.checkJson(message.content());
    else guard.check(message.content());
    guard.check(message.toolCallId());
    for (var call : message.toolCalls()) {
      guard.check(call.id());
      guard.check(call.name());
      guard.checkJson(call.argumentsJson());
    }
  }

  private void check(ModelMessage message) {
    if (message.role() == ModelMessage.Role.TOOL) guard.checkJson(message.content());
    else guard.check(message.content());
    guard.check(message.toolCallId());
    for (var call : message.toolCalls()) {
      guard.check(call.id());
      guard.check(call.name());
      guard.checkJson(call.argumentsJson());
    }
    if (ContextSizer.messageBytes(message) > budget.maxMessageBytes())
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
  }

  public ProcessingHandle commitToolObservation(
      ProcessingHandle handle, UUID group, ToolObservation observation) {
    guard.checkJson(observation.data());
    var encoded = ToolObservationJson.encode(observation);
    guard.checkJson(encoded);
    if (ContextSizer.utf8(observation.data()) > budget.maxToolResultBytes())
      throw new AgentException(AgentErrorCode.CONTEXT_LIMIT);
    check(new ModelMessage(ModelMessage.Role.TOOL, encoded, observation.callId(), List.of()));
    return store.commitToolObservation(handle, group, observation);
  }

  public Optional<ToolObservation> findCommittedObservation(ProcessingHandle handle, String id) {
    guard.check(id);
    var value = store.findCommittedObservation(handle, id);
    value.ifPresent(o -> guard.checkJson(ToolObservationJson.encode(o)));
    return value;
  }

  public ProcessingHandle recordEvent(
      ProcessingHandle h, Integer n, UUID g, String type, String reason, long count) {
    return store.recordEvent(h, n, g, type, reason, count);
  }

  public ProcessingResult finish(
      ProcessingHandle handle, ProcessingStatus status, AgentErrorCode error, String reply) {
    guard.check(reply);
    var result = store.finish(handle, status, error, reply);
    if (!result.persistenceConfirmed())
      throw new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
    return result;
  }

  public Optional<String> recallLongTerm(MemoryQuery query) {
    var value = Objects.requireNonNull(longTerm.recall(query));
    value.ifPresent(guard::check);
    if (value.isPresent() && ContextSizer.utf8(value.get()) > query.maxBytes())
      throw new LongTermMemoryLimitException();
    return value;
  }
}

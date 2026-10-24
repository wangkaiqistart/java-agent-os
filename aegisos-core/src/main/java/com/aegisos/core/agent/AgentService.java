package com.aegisos.core.agent;

import com.aegisos.core.context.SensitiveContentGuard;
import com.aegisos.core.memory.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.provider.SessionAssociation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** 所有入口的统一同步边界；显式会话归属、非等待锁及 finally 作用域清理。 */
public final class AgentService {
  private final ProfileRegistry profiles;
  private final MemoryService memory;
  private final ReActLoop loop;
  private final SensitiveContentGuard guard;
  private final UUID runtime;
  private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock();
    int references;
  }

  public AgentService(
      ProfileRegistry profiles,
      MemoryService memory,
      ReActLoop loop,
      SensitiveContentGuard guard,
      UUID runtimeInstanceId) {
    this.profiles = profiles;
    this.memory = memory;
    this.loop = loop;
    this.guard = guard;
    this.runtime = Objects.requireNonNull(runtimeInstanceId);
  }

  private Profile profile(String name) {
    guard.check(name);
    var p = profiles.get(name);
    if (p == null) throw new AgentException(AgentErrorCode.UNKNOWN_PROFILE);
    return p;
  }

  /** 多渠道复用同一集中绑定入口，CLI不生成或拼接身份主键。 */
  public SessionSnapshot getOrCreateSession(SessionIdentity identity) {
    Objects.requireNonNull(identity);
    guard.check(identity.channel());
    guard.check(identity.user());
    profile(identity.profile());
    return memory.getOrCreateSession(identity);
  }

  public SessionSnapshot createSession(String profileName) {
    profile(profileName);
    return memory.createSession(profileName);
  }

  public ProcessingResult process(AgentRequest request) {
    String confirmedSession = null;
    try {
      if (request == null || ProfileContext.current() != null)
        throw new AgentException(AgentErrorCode.INVALID_INPUT);
      guard.check(request.sessionId());
      guard.check(request.profileName());
      guard.check(request.userMessage());
      if (SessionAssociation.safe(request.sessionId()) == null
          || request.userMessage() == null
          || request.userMessage().isBlank())
        throw new AgentException(AgentErrorCode.INVALID_INPUT);
      var selected = profile(request.profileName());
      var snapshot = memory.loadSession(request.sessionId(), selected.name());
      confirmedSession = snapshot.sessionId();
      String session = confirmedSession;
      Entry entry =
          locks.compute(
              session,
              (key, existing) -> {
                var value = existing == null ? new Entry() : existing;
                value.references++;
                return value;
              });
      boolean acquired = false;
      try {
        acquired = entry.lock.tryLock();
        if (!acquired) throw new AgentException(AgentErrorCode.SESSION_BUSY);
        try (var scope = ProfileContext.enter(selected.name())) {
          var handle =
              memory.beginProcessing(session, selected.name(), request.userMessage(), runtime);
          return loop.run(selected, handle);
        }
      } finally {
        if (acquired) entry.lock.unlock();
        locks.computeIfPresent(
            session,
            (key, value) -> {
              value.references--;
              return value.references == 0 ? null : value;
            });
      }
    } catch (AgentException e) {
      return new ProcessingResult(
          confirmedSession, null, ProcessingStatus.FAILED, 0, null, e.code(), false);
    } catch (RuntimeException e) {
      return new ProcessingResult(
          confirmedSession,
          null,
          ProcessingStatus.FAILED,
          0,
          null,
          AgentErrorCode.PERSISTENCE_UNCONFIRMED,
          false);
    }
  }
}

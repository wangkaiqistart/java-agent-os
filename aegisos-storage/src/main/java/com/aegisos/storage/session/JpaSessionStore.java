package com.aegisos.storage.session;

import com.aegisos.core.agent.*;
import com.aegisos.core.memory.*;
import com.aegisos.core.provider.*;
import com.aegisos.core.tool.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQLite 会话后端。只通过受控参数执行原生 JPA 查询，所有写入均是独立短事务。 handle.version 属于处理记录；消息序号使用 sessions.version 的另一条
 * CAS，二者不可混用。 无任何网络调用处于本类事务内；返回值只在 TransactionTemplate 确认提交后交给 Memory。
 */
public final class JpaSessionStore implements SessionStore {
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  // 仅持有“取得当前短事务EntityManager”的能力，不向仓库暴露工厂生命周期/可变配置。
  private final java.util.function.Supplier<EntityManager> currentEntityManager;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public JpaSessionStore(
      EntityManagerFactory emf, PlatformTransactionManager manager, Clock clock) {
    Objects.requireNonNull(emf);
    currentEntityManager =
        () ->
            Objects.requireNonNull(
                EntityManagerFactoryUtils.getTransactionalEntityManager(emf),
                "TRANSACTION_REQUIRED");
    this.clock = Objects.requireNonNull(clock);
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private <T> T transaction(Function<EntityManager, T> action) {
    try {
      return transactions.execute(status -> action.apply(currentEntityManager.get()));
    } catch (AgentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw failure();
    }
  }

  private String now() {
    return clock.instant().toString();
  }

  private static AgentException failure() {
    return new AgentException(AgentErrorCode.PERSISTENCE_UNCONFIRMED);
  }

  private static Query query(EntityManager em, String sql, Object... args) {
    var query = em.createNativeQuery(sql);
    for (int i = 0; i < args.length; i++) query.setParameter(i + 1, args[i]);
    return query;
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(EntityManager em, String sql, Object... args) {
    return query(em, sql, args).getResultList();
  }

  private static int update(EntityManager em, String sql, Object... args) {
    return query(em, sql, args).executeUpdate();
  }

  private static long number(Object n) {
    return n instanceof Boolean b ? (b ? 1L : 0L) : ((Number) n).longValue();
  }

  private static String text(Object s) {
    return s == null ? null : s.toString();
  }

  private static void one(int count) {
    if (count != 1) throw failure();
  }

  public SessionSnapshot createSession(String profile) {
    com.aegisos.core.profile.Profile.requireName(profile);
    return transaction(
        em -> {
          String id = UUID.randomUUID().toString(), time = now();
          one(
              update(
                  em,
                  "INSERT INTO sessions(id,profile_name,created_at,updated_at,version,next_sequence_no) VALUES(?1,?2,?3,?3,0,1)",
                  id,
                  profile,
                  time));
          return new SessionSnapshot(
              id, profile, Instant.parse(time), Instant.parse(time), 0, List.of());
        });
  }

  private Object[] session(EntityManager em, String id, String profile) {
    var rows =
        rows(
            em,
            "SELECT id,profile_name,created_at,updated_at,version,next_sequence_no FROM sessions WHERE id=?1",
            id);
    if (rows.isEmpty()) throw new AgentException(AgentErrorCode.UNKNOWN_SESSION);
    var row = rows.getFirst();
    if (!Objects.equals(profile, text(row[1])))
      throw new AgentException(AgentErrorCode.SESSION_PROFILE_MISMATCH);
    return row;
  }

  public SessionSnapshot loadSession(String id, String profile) {
    return transaction(
        em -> {
          var row = session(em, id, profile);
          // 有界恢复视图用于身份/检查；Prompt 的完成轮次窗口另行查询，不一次装载整个历史库。
          var selected =
              rows(
                  em,
                  "SELECT role,content,tool_call_id,tool_calls_json FROM session_messages WHERE session_id=?1 ORDER BY sequence_no DESC LIMIT 1000",
                  id);
          var messages = new ArrayList<ModelMessage>();
          for (int i = selected.size() - 1; i >= 0; i--) messages.add(message(selected.get(i)));
          return new SessionSnapshot(
              id,
              profile,
              Instant.parse(text(row[2])),
              Instant.parse(text(row[3])),
              number(row[4]),
              messages);
        });
  }

  public ProcessingHandle beginProcessing(String id, String profile, String user, UUID runtime) {
    return transaction(
        em -> {
          session(em, id, profile);
          if (!query(
                  em,
                  "SELECT id FROM agent_processings WHERE session_id=?1 AND status='RUNNING'",
                  id)
              .getResultList()
              .isEmpty()) throw new AgentException(AgentErrorCode.SESSION_BUSY);
          UUID process = UUID.randomUUID();
          String time = now();
          one(
              update(
                  em,
                  "INSERT INTO agent_processings(id,session_id,runtime_instance_id,status,started_at,iteration_count,version) VALUES(?1,?2,?3,'RUNNING',?4,0,0)",
                  process.toString(),
                  id,
                  runtime.toString(),
                  time));
          UUID group = UUID.randomUUID();
          one(
              update(
                  em,
                  "INSERT INTO message_exchange_groups VALUES(?1,?2,0,'USER','CLOSED',NULL,0,0)",
                  group.toString(),
                  process.toString()));
          var handle = new ProcessingHandle(process, id, profile, runtime, 0);
          append(em, handle, group, ModelMessage.Role.USER, user, null, null);
          return handle;
        });
  }

  /** 每次读取/追加都验证会话、Profile、运行实例、RUNNING 与处理版本；没有按 UUID 猜归属。 */
  private Object[] require(EntityManager em, ProcessingHandle h) {
    if (h == null) throw failure();
    var selected =
        rows(
            em,
            "SELECT p.session_id,s.profile_name,p.runtime_instance_id,p.version,p.status,p.iteration_count FROM agent_processings p JOIN sessions s ON s.id=p.session_id WHERE p.id=?1",
            h.processId().toString());
    if (selected.size() != 1) throw failure();
    var row = selected.getFirst();
    if (!Objects.equals(h.sessionId(), text(row[0]))
        || !Objects.equals(h.profileName(), text(row[1]))
        || !Objects.equals(h.runtimeInstanceId().toString(), text(row[2]))
        || number(row[3]) != h.version()
        || !"RUNNING".equals(text(row[4]))) throw failure();
    return row;
  }

  private ProcessingHandle claim(EntityManager em, ProcessingHandle h) {
    require(em, h);
    one(
        update(
            em,
            "UPDATE agent_processings SET version=version+1 WHERE id=?1 AND session_id=?2 AND runtime_instance_id=?3 AND version=?4 AND status='RUNNING'",
            h.processId().toString(),
            h.sessionId(),
            h.runtimeInstanceId().toString(),
            h.version()));
    return new ProcessingHandle(
        h.processId(), h.sessionId(), h.profileName(), h.runtimeInstanceId(), h.version() + 1);
  }

  private void append(
      EntityManager em,
      ProcessingHandle h,
      UUID group,
      ModelMessage.Role role,
      String content,
      String callId,
      String callsJson) {
    var selected = session(em, h.sessionId(), h.profileName());
    long version = number(selected[4]), sequence = number(selected[5]);
    one(
        update(
            em,
            "UPDATE sessions SET version=version+1,next_sequence_no=next_sequence_no+1,updated_at=?1 WHERE id=?2 AND version=?3",
            now(),
            h.sessionId(),
            version));
    one(
        update(
            em,
            "INSERT INTO session_messages(id,session_id,process_id,sequence_no,group_id,role,content,tool_call_id,tool_calls_json,created_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            UUID.randomUUID().toString(),
            h.sessionId(),
            h.processId().toString(),
            sequence,
            group.toString(),
            role.name(),
            content,
            callId,
            callsJson,
            now()));
  }

  public ProcessingHandle prepareModelTurn(ProcessingHandle h, int iteration) {
    return transaction(
        em -> {
          var row = require(em, h);
          if (iteration < 1 || number(row[5]) + 1 != iteration) throw failure();
          var next = claim(em, h);
          one(
              update(
                  em,
                  "INSERT INTO model_turns(process_id,iteration_no,status,prepared_at) VALUES(?1,?2,'PREPARED',?3)",
                  h.processId().toString(),
                  iteration,
                  now()));
          return next;
        });
  }

  public Acceptance acceptModelResponse(ProcessingHandle h, int iteration, ModelResponse response) {
    return transaction(
        em -> {
          var row = require(em, h);
          if (response == null
              || response.requestId() == null
              || (response.content() == null || response.content().isBlank())
                  && response.toolCalls().isEmpty()
              || number(row[5]) + 1 != iteration)
            throw new AgentException(AgentErrorCode.INVALID_RESPONSE);
          var links =
              query(
                      em,
                      "SELECT request_id FROM llm_request_links WHERE process_id=?1 AND iteration_no=?2",
                      h.processId().toString(),
                      iteration)
                  .getResultList();
          if (links.size() != 1
              || !response.requestId().toString().equals(links.getFirst().toString()))
            throw failure();
          var next = claim(em, h);
          one(
              update(
                  em,
                  "UPDATE model_turns SET status='ACCEPTED',completed_at=?1 WHERE process_id=?2 AND iteration_no=?3 AND status='PREPARED'",
                  now(),
                  h.processId().toString(),
                  iteration));
          UUID group = UUID.randomUUID();
          one(
              update(
                  em,
                  "INSERT INTO message_exchange_groups VALUES(?1,?2,?3,?4,?5,?3,?6,0)",
                  group.toString(),
                  h.processId().toString(),
                  iteration,
                  response.toolCalls().isEmpty() ? "TEXT" : "TOOL_EXCHANGE",
                  response.toolCalls().isEmpty() ? "CLOSED" : "OPEN",
                  response.toolCalls().size()));
          append(
              em,
              next,
              group,
              ModelMessage.Role.ASSISTANT,
              response.content(),
              null,
              response.toolCalls().isEmpty()
                  ? null
                  : JSON.writeValueAsString(response.toolCalls()));
          one(
              update(
                  em,
                  "UPDATE agent_processings SET iteration_count=iteration_count+1 WHERE id=?1 AND version=?2 AND status='RUNNING'",
                  h.processId().toString(),
                  next.version()));
          return new Acceptance(next, group, iteration);
        });
  }

  public ContextHistory loadContextHistory(ProcessingHandle h, int max) {
    if (max < 1 || max > 1000) throw new AgentException(AgentErrorCode.INVALID_INPUT);
    return transaction(
        em -> {
          require(em, h);
          var ids =
              query(
                      em,
                      "SELECT p.id FROM agent_processings p JOIN session_messages m ON m.process_id=p.id AND m.role='USER' WHERE p.session_id=?1 AND p.status='COMPLETED' GROUP BY p.id ORDER BY min(m.sequence_no) DESC LIMIT ?2",
                      h.sessionId(),
                      max)
                  .getResultList();
          var completed = new ArrayList<List<ExchangeGroup>>();
          for (int i = ids.size() - 1; i >= 0; i--)
            completed.add(groups(em, ids.get(i).toString()));
          return new ContextHistory(completed, groups(em, h.processId().toString()));
        });
  }

  private List<ExchangeGroup> groups(EntityManager em, String process) {
    var selected =
        rows(
            em,
            "SELECT id,group_ordinal,kind,state,iteration_no,expected_observations,committed_observations FROM message_exchange_groups WHERE process_id=?1 AND state='CLOSED' ORDER BY group_ordinal",
            process);
    var groups = new ArrayList<ExchangeGroup>();
    for (var row : selected) {
      var messages =
          rows(
                  em,
                  "SELECT role,content,tool_call_id,tool_calls_json FROM session_messages WHERE group_id=?1 AND process_id=?2 ORDER BY sequence_no",
                  text(row[0]),
                  process)
              .stream()
              .map(JpaSessionStore::message)
              .toList();
      groups.add(
          new ExchangeGroup(
              UUID.fromString(text(row[0])),
              UUID.fromString(process),
              (int) number(row[1]),
              ExchangeGroup.Kind.valueOf(text(row[2])),
              ExchangeGroup.State.valueOf(text(row[3])),
              row[4] == null ? null : (int) number(row[4]),
              (int) number(row[5]),
              (int) number(row[6]),
              messages));
    }
    return List.copyOf(groups);
  }

  private static ModelMessage message(Object[] row) {
    List<ToolCall> calls =
        row[3] == null ? List.of() : List.of(JSON.readValue(text(row[3]), ToolCall[].class));
    return new ModelMessage(
        ModelMessage.Role.valueOf(text(row[0])), text(row[1]), text(row[2]), calls);
  }

  public ProcessingResult finish(
      ProcessingHandle h, ProcessingStatus status, AgentErrorCode error, String reply) {
    return transaction(
        em -> {
          var row = require(em, h);
          if (status == null
              || status == ProcessingStatus.RUNNING
              || status == ProcessingStatus.COMPLETED
                  && (error != null || reply == null || reply.isBlank())
              || status != ProcessingStatus.COMPLETED && error == null) throw failure();
          if (status == ProcessingStatus.COMPLETED) {
            var last =
                query(
                        em,
                        "SELECT content FROM session_messages WHERE process_id=?1 AND role='ASSISTANT' ORDER BY sequence_no DESC LIMIT 1",
                        h.processId().toString())
                    .getResultList();
            if (last.size() != 1 || !reply.equals(last.getFirst())) throw failure();
          }
          one(
              update(
                  em,
                  "UPDATE agent_processings SET status=?1,completed_at=?2,error_code=?3,version=version+1 WHERE id=?4 AND version=?5 AND status='RUNNING'",
                  status.name(),
                  now(),
                  error == null ? null : error.name(),
                  h.processId().toString(),
                  h.version()));
          update(
              em,
              "UPDATE model_turns SET status=?1,completed_at=?2,error_code=?3 WHERE process_id=?4 AND status='PREPARED'",
              status == ProcessingStatus.INTERRUPTED ? "INTERRUPTED" : "FAILED",
              now(),
              error == null ? AgentErrorCode.INVALID_RESPONSE.name() : error.name(),
              h.processId().toString());
          return new ProcessingResult(
              h.sessionId(),
              h.processId(),
              status,
              (int) number(row[5]),
              status == ProcessingStatus.COMPLETED ? reply : null,
              error,
              true);
        });
  }

  public ProcessingHandle commitToolObservation(
      ProcessingHandle h, UUID group, ToolObservation observation) {
    return transaction(
        em -> {
          require(em, h);
          var groups =
              rows(
                  em,
                  "SELECT expected_observations,committed_observations FROM message_exchange_groups WHERE id=?1 AND process_id=?2 AND state='OPEN' AND kind='TOOL_EXCHANGE'",
                  group.toString(),
                  h.processId().toString());
          if (groups.size() != 1) throw failure();
          var intents =
              rows(
                  em,
                  "SELECT role,content,tool_call_id,tool_calls_json FROM session_messages WHERE group_id=?1 AND process_id=?2 AND role='ASSISTANT'",
                  group.toString(),
                  h.processId().toString());
          if (intents.size() != 1
              || message(intents.getFirst()).toolCalls().stream()
                  .noneMatch(
                      c ->
                          c.id().equals(observation.callId())
                              && c.name().equals(observation.name()))) throw failure();
          if (!query(
                  em,
                  "SELECT id FROM session_messages WHERE group_id=?1 AND role='TOOL' AND tool_call_id=?2",
                  group.toString(),
                  observation.callId())
              .getResultList()
              .isEmpty()) throw failure();
          var audits =
              rows(
                  em,
                  "SELECT status,success,error_code,external_outcome,tool_name FROM tool_invocations WHERE id=?1 AND process_id=?2 AND session_id=?3 AND raw_call_id=?4",
                  observation.auditId().toString(),
                  h.processId().toString(),
                  h.sessionId(),
                  observation.callId());
          if (audits.size() != 1) throw failure();
          var audit = audits.getFirst();
          if (!Set.of("SUCCEEDED", "FAILED", "REJECTED").contains(text(audit[0]))
              || number(audit[1]) != (observation.success() ? 1 : 0)
              || !Objects.equals(
                  text(audit[2]),
                  observation.errorCode() == null ? null : observation.errorCode().name())
              || !Objects.equals(text(audit[3]), observation.externalOutcome().name())
              || !Objects.equals(text(audit[4]), observation.name())) throw failure();
          var next = claim(em, h);
          append(
              em,
              next,
              group,
              ModelMessage.Role.TOOL,
              ToolObservationJson.encode(observation),
              observation.callId(),
              null);
          one(
              update(
                  em,
                  "UPDATE message_exchange_groups SET committed_observations=committed_observations+1,state=CASE WHEN committed_observations+1=expected_observations THEN 'CLOSED' ELSE 'OPEN' END WHERE id=?1 AND process_id=?2 AND state='OPEN'",
                  group.toString(),
                  h.processId().toString()));
          return next;
        });
  }

  public Optional<ToolObservation> findCommittedObservation(ProcessingHandle h, String id) {
    return transaction(
        em -> {
          require(em, h);
          var found =
              rows(
                  em,
                  "SELECT m.content,a.id,a.tool_name,a.status FROM session_messages m JOIN message_exchange_groups g ON g.id=m.group_id JOIN tool_invocations a ON a.process_id=m.process_id AND a.raw_call_id=m.tool_call_id WHERE m.process_id=?1 AND m.role='TOOL' AND m.tool_call_id=?2 AND g.state='CLOSED' ORDER BY m.sequence_no LIMIT 1",
                  h.processId().toString(),
                  id);
          if (found.isEmpty()) return Optional.empty();
          var row = found.getFirst();
          if (!Set.of("SUCCEEDED", "FAILED", "REJECTED").contains(text(row[3])))
            return Optional.empty();
          var value = JSON.readValue(text(row[0]), ToolObservation.class);
          if (!value.auditId().toString().equals(text(row[1]))
              || !value.name().equals(text(row[2]))) throw failure();
          return Optional.of(value);
        });
  }

  public ProcessingHandle recordEvent(
      ProcessingHandle h, Integer iteration, UUID group, String type, String reason, long count) {
    if (!Set.of("WARN", "TRUNCATED", "TOOL_REUSED", "TOOL_CONFLICT", "STOPPED", "RECOVERED")
            .contains(type)
        || count < 0) throw failure();
    if (!Set.of(
            "OPTIONAL_FILE_MISSING",
            "CONTEXT_TRIMMED",
            "RELIABLE_OBSERVATION",
            "HISTORY_PROCESSINGS_TRIMMED",
            "CURRENT_GROUPS_TRIMMED",
            "OPTIONAL_MEMORY_TRIMMED",
            "CONTEXT_BYTES_TRIMMED")
        .contains(reason))
      try {
        AgentErrorCode.valueOf(reason);
      } catch (RuntimeException e) {
        throw failure();
      }
    return transaction(
        em -> {
          var next = claim(em, h);
          one(
              update(
                  em,
                  "INSERT INTO processing_events VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
                  UUID.randomUUID().toString(),
                  h.processId().toString(),
                  iteration,
                  group == null ? null : group.toString(),
                  type,
                  now(),
                  reason,
                  count));
          return next;
        });
  }
}

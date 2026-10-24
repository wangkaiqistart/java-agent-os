# Agent、Memory 与 Provider 契约

**性质**：待实施公共 Java 契约；以下签名为设计形状，不表示当前已有类。已有 Provider 三参数入口继续兼容。实体及事务详见 [数据模型](../data-model.md)。

## 1. 模块归属

- core：AgentService、AgentRequest/ProcessingResult、ReActLoop、MemoryService、SessionStore、LongTermMemoryStore、ProviderInvocation 等公共契约/领域编排。
- memory：DefaultMemoryService，唯一协调会话存取与可选长期记忆的实现；默认 EmptyLongTermMemoryStore 不进行外呼或文件读取。
- storage：JpaSessionStore、JpaToolInvocationAuditStore、既有 JpaLlmCallAuditStore、schema 与运行锁。
- provider：原 DefaultProviderService，继续是唯一模型审计/重试执行边界。
- boot：构建上述对象与配置，不由 core 扫描 Bean。

## 2. Agent 入口

`AgentService.createSession(profileName)` 返回持久 SessionSnapshot；`AgentService.process(AgentRequest(sessionId, profileName, userMessage))` 返回 ProcessingResult。CLI 创建或恢复身份后均调用此入口。

- profileName 必须精确注册；sessionId 必须存在且绑定同名 Profile。空/非法/未知/错归属拒绝，不能静默新建。
- 每次合法新请求生成 processId；重复用户提交是新处理，不提供跨请求幂等承诺。工具去重仅在同一 processId 内有效。
- 同会话竞争返回 SESSION_BUSY，不等待前请求、不发起模型；不同会话可独立执行，受全局资源上限约束。
- 设置 ProfileContext 后整个同步执行用 finally 清理；不得通过异常、保存失败或线程复用遗留状态。
- 返回字段：sessionId/processId/status/iterationCount、可空安全 reply、固定 errorCode、persistenceConfirmed。无持久确认不能输出“已保存/已完成”。UNKNOWN 是确认信息，不凭空增加可恢复的已提交终态。
- 固定错误至少涵盖 INVALID_INPUT、UNKNOWN_PROFILE、UNKNOWN_SESSION、SESSION_PROFILE_MISMATCH、SESSION_BUSY、CONTEXT_LIMIT、CONTEXT_FILE_UNAVAILABLE、SENSITIVE_CONTENT、TOOL_CALL_CONFLICT、PROVIDER_FAILURE、AUDIT_UNAVAILABLE、PERSISTENCE_UNCONFIRMED、INTERRUPTED。原 Provider 错误保留安全 code/requestId，不透传 Throwable.getMessage。

## 3. MemoryService

| 操作 | 输入/输出与提交保证 |
| --- | --- |
| createSession / loadSession | 创建或严格按身份/归属读取；不会通过任意查询读其他会话 |
| beginProcessing | 校验安全输入后提交 RUNNING 与 USER；返回带版本的 handle |
| loadContextHistory | 返回已提交、按组有序的当前处理与完成历史；返回范围受窗口和容量约束，不将整个库一次加载到内存 |
| prepareModelTurn | 外呼前提交模型轮次 PREPARED 元数据 |
| acceptModelResponse | 校验后保存完整响应、组、ACCEPTED 及 iterationCount；同事务完成 |
| commitToolObservation | 保存安全有界观察，增加组完成数，全部结果提交才闭合 |
| findCommittedObservation | 按 process+callId 检查同名/同规范参数；只复用审计终态和观察都可靠提交的记录 |
| recordEvent / finish | 固定安全事件及终态；COMPLETED 必须在最终安全回复已提交后确认 |
| recallLongTerm | 受限 MemoryQuery → 可选 LongTermContext；默认空，测试可注入替身 |

循环不直接调 SessionStore/JPA 或读 MEMORY.md。存储端口只由 Memory 实现消费；tool 的审计端口只写元数据，不替代 Memory 访问。追加/结束均验证 handle 的会话、处理、版本、RUNNING 状态。CAS 命中数不是 1 即失败，不能覆盖竞争状态。

## 4. 循环顺序

1. 使用 Memory 的已提交视图构建 Prompt；若任何必需内容不安全或超限，零模型请求。
2. 准备 model_turn；调用 `chatProfile(sessionId, profileName, prompt, invocation)`。
3. 校验整份响应，空/空白文本且无工具为协议错误；文本与工具同时存在保留两者。批量工具数/身份/重复 ID 检查先于执行；敏感响应不保存。
4. Memory 接受并提交响应，迭代加一。无工具有效文本则 finish(COMPLETED)；含工具且已到 N 则 finish(MAX_ITERATIONS)，不执行该响应工具。
5. 其余按顺序执行，可靠业务失败也形成结构化观察。每个观察提交后才可使用；所有观察闭合后进入下一轮。
6. Provider、保存或审计故障停止；不在循环外重试。恢复只恢复数据，下一个用户请求开启新处理。

N=10、每轮一个新工具意图：逻辑模型请求=10、接受响应=10、工具执行最多9，末轮=0。N=1：工具响应不执行；文本响应正常完成。Provider 内部 retry 不增加 accepted iteration；同一响应工具数量上限另受策略控制。

## 5. Provider 兼容扩展

既有三参数入口与旧直接入口不删除；新增四参数重载，第四参数为 `ProviderInvocation(UUID processId, int iterationNo)`。原入口调用同一内部链时 invocation=null，循环必须显式提供。不能用默认方法丢弃 invocation 假装已关联。

- requestId 仍由 DefaultProviderService 每个逻辑请求生成；同一请求的物理 attempt 保持 requestId、递增 attemptNo，配置的有限网络/超时重试规则不变。
- 扩展 AuditStart/AuditRejection 时保留原五/六参数构造器，使原测试与旧客户端明确获得空处理关联。
- begin/reject 在同事务写 link+审计；检验 invocation 对应已准备、RUNNING 处理及相同 session。第一次失败后不发送；重试不能创建第二个 requestId 或重绑 iteration。
- 有效 invocation 下的成功/失败/拒绝都能精确关联。未知 process、未准备 iteration、错 session 或已终止处理在外呼前拒绝为 INVALID_REQUEST，提交安全的无处理关联拒绝审计，不存储不受信任的 process/session 值、不伪造 link；只有有效关联的链接/审计提交故障才是 AUDIT_UNAVAILABLE。增加公共重载的上述反例测试。
- Provider 自身完成审计后，Agent 再发现敏感输出或保存失败，只改变 Agent 处理结果，保留真实模型调用事实。
- SDK 自动工具执行仍为零，SchemaDescriptor 无可执行实现；原 `OpenAiMessageMapper` 的严格工具配对/全局唯一校验不放松。
- 为避免无限响应缓冲，在 SingleRequestHttpClient 解码前最多读 1 MiB+1；超限按不可重试协议错误记录已收到响应，关闭流。现有端点、零重定向/重发、敏感日志屏蔽继续保留。

新增边界必须回归原 002/005 全部测试；旧库关联为空不是错误，不把 US1 仅追踪的 sessionId 语义倒改成强制会话存在。

## 6. 关键契约测试

AgentServiceTest、ReActLoopTest、MemoryServiceTest、SessionPersistenceTest、ProcessingCorrelationTest、RuntimeRecoveryTest（均计划新增）：覆盖无工具/多轮、跨重启、同会话 busy、错归属零外呼、begin/complete/最终保存故障、处理关联的前置事务、N=1/2/10、ThreadLocal 清理、无新增 Provider 重试及敏感输出保留真实审计。

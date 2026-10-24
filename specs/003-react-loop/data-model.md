# US2 数据模型与恢复设计

**状态**：Phase 1 设计，尚未建表或迁移。依据 [规格](spec.md) 与 [研究 R01～R09](research.md)。

## 1. 身份与公共对象

| 对象 | 字段与约束 | 归属/关系 |
| --- | --- | --- |
| SessionSnapshot | sessionId：运行时 UUID 字符串；profileName：已注册精确名称；时间、version、已提交消息视图 | core 契约；一个会话不能随请求改 Agent |
| ProcessingHandle | processId：UUID；sessionId；profileName；runtimeInstanceId；版本 | 一个用户请求一个处理；不接受外部伪造已有处理身份 |
| ProcessingResult | sessionId/processId/status/iterationCount；可空最终安全回复；固定 errorCode；持久化确认标记 | COMPLETED 仅在终态提交后返回；其他状态不冒充完成 |
| ModelTurn | processId、iterationNo（从 1 开始）、状态、可空 requestId | 一个逻辑模型请求；内部 Provider 多 attempt 不增加迭代次数 |
| AcceptedResponse | 内容、原始 ToolCall 列表、requestId、usage 元数据 | 全字段通过容量/敏感检查后才作为已接受响应计数和保存 |
| ExchangeGroup | groupId、processId、序号、类型、OPEN/CLOSED、expected/committedObservationCount | 工具意图及全部结果是不可拆的发送/裁剪单位 |
| ToolObservation | 原始 callId、name、success、errorCode、externalOutcome、受限 data | 对话状态；不是原始 Java 异常，不进入操作日志 |
| ProviderInvocation | processId、iterationNo | 显式传给 Provider 的可选关联，不承载正文或改变路由 |
| AgentSource | profileName、工作区规范根、启动真实 Agent 目录、相对目录 | 启动只读来源索引，不属于会话持久化；不按 Profile.name 推算路径 |

sessionId 仍满足 US1 的 1～64 位 ASCII 规则，US2 进一步要求真实存在及 Profile 归属一致。callId 仅接受 1～128 位 `[A-Za-z0-9_.-]`；工具名只保留安全格式与注册标识。无效身份不原样记录/回显；无法保留原值时使用生成的事件身份与固定原因。领域 DTO 的 `toString` 不包含正文、参数或凭据。

## 2. 持久表

全部新表使用 SQLite，键以 TEXT 保存 UUID，时间为 UTC ISO-8601 文本。外键在**每条连接**启用并测试，不能只在建表连接执行一次 PRAGMA。正文是本机受权限保护的必要对话数据，并非加密存储；不宣称加密静态数据。对话/参数/结果写入前及恢复加载后均执行安全检查。

### 2.1 sessions

- `id` 主键；`profile_name` 非空且有界；`created_at/updated_at`；`version >= 0`；`next_sequence_no >= 1`。
- profile_name 是启动注册表的逻辑身份，不外键关联文件系统。Profile 暂不可用时保留会话，拒绝运行，不改归属。
- 分配消息序号和 CAS 更新 version 在同一事务；唯一 `(session_id,sequence_no)` 防覆盖。

### 2.2 agent_processings

- `id` 主键；`session_id` 外键；`runtime_instance_id`；`status`；`started_at/completed_at`；`iteration_count >= 0`；`error_code` 可空固定枚举；`version`。
- `RUNNING` 无 completed_at；其他终态有 completed_at。COMPLETED 无 error_code，其余有安全停止原因（例如 MAX_ITERATIONS、PROVIDER_FAILURE、SENSITIVE_CONTENT、RECOVERED_INTERRUPTION）。
- 部分唯一索引 `session_id WHERE status='RUNNING'`；同会话竞争先 try-lock，再依赖此约束防遗漏。数据库锁争用/约束失败不得被循环当作可重试的模型调用。
- 状态只允许 RUNNING 转一次终态，不允许终态重开；同会话新请求创建新处理。

### 2.3 model_turns 与 llm_request_links

- `model_turns`：主键 `(process_id,iteration_no)`；session 通过处理关联；`status` 为 PREPARED/ACCEPTED/FAILED/INTERRUPTED；准备/结束时间；固定错误码。PREPARED 在调用 Provider 之前提交。
- `llm_request_links`：`request_id` 主键；`process_id/iteration_no` 外键 model_turns；唯一 `(process_id,iteration_no)`。
- Provider 生成 requestId 后，`audit.begin` 的同一事务创建 link 和第一条 llm_calls；参数拒绝则 `audit.reject` 同事务创建 link 与 attempt_no=0 行。后续 attempt 对相同 link 做幂等一致性校验；不允许重绑到其他处理。
- 连接事务验证 llm_calls.session_id 与该处理的会话相同。旧 Provider 入口仍可没有 link；既有审计不回填。link 缺失且模型尚未发送的失败，可从 PREPARED/FAILED 判断，不伪造外呼记录。
- `iteration_count` 只在安全响应被接受/持久化的同事务加一；请求失败不算接受响应。既有 llm_calls 保留实际物理 attempt、用量、成功/失败事实。

### 2.4 message_exchange_groups 与 session_messages

- `message_exchange_groups`：id、process_id、group_ordinal、kind（USER/TEXT/TOOL_EXCHANGE）、state（OPEN/CLOSED）、可空 iteration_no、expected_observations、committed_observations。唯一 `(process_id,group_ordinal)`；工具组仅在全部结果提交后 CLOSED。
- `session_messages`：id、session_id、process_id、sequence_no、group_id、role、content、可空原始 tool_call_id、可空 tool_calls_json、created_at。ASSISTANT 可同时保留正文与工具列表；TOOL 有且只有一个原始引用；USER 不携带工具元数据。
- tool_calls_json 是必要对话状态，保存通过校验的有界原参数；**不复制到 tool_invocations 或日志**。content/整个消息按 UTF-8 预算检查；不能截断 JSON 后保存。
- 消息与组必须属于同一个处理/会话，写入端验证，数据库用复合唯一键/外键或等价 CHECK 保证，迁移测试包含错归属反例。所有序号均唯一递增。
- 普通历史查询只选择最近最多 20 个 COMPLETED 处理，内部按 sequence_no 原序回放；其他终态与 OPEN 组仍可从恢复视图检查，但不自动送入下一次 Prompt。当前处理只使用已提交 USER/TEXT 和 CLOSED 工具组。
- 第 N 次工具响应保存为 OPEN 组后以 MAX_ITERATIONS 结束，不虚构 TOOL 结果。裁剪仅创建发送投影，不 DELETE 持久记录。

### 2.5 tool_invocations

| 字段 | 规则 |
| --- | --- |
| id/session_id/process_id | 独立审计 UUID；会话、处理归属一致 |
| raw_call_id/tool_name | 安全有界；唯一 `(process_id,raw_call_id)`；未知工具使用通过格式校验的名称，否则固定标识 |
| first_iteration_no/call_ordinal | 第一次意图位置；重用不新执行，也不覆盖第一次审计 |
| arguments_fingerprint | 工具名 + 规范 JSON 参数的 SHA-256；拒绝敏感内容后才计算；不是保密手段 |
| status | STARTED/SUCCEEDED/FAILED/REJECTED/UNKNOWN |
| started_at/completed_at/duration_ms | STARTED 尚未完成；终态时间及非负耗时完整 |
| success/error_code/error_message | STARTED 均空；SUCCEEDED=true、错误空；其他终态=false、固定代码/安全说明（1～256 字符） |
| external_outcome | NOT_SENT/RESPONDED/UNKNOWN；与业务 success 分开 |

参数/授权/Sandbox 拒绝通过同一端口一次性提交 REJECTED，实际执行次数为零；允许执行先提交 STARTED，完成后另一个短事务写终态。工具业务错误/超时可以形成可靠的失败观察；若无法证明外部结果，external_outcome=UNKNOWN，禁止同调用自动重放。

`audit.complete` 提交后，MemoryService 提交对应 TOOL 消息；两步之间崩溃时保留真实终态审计，但观察未提交不可被复用或送模型，本次处理停止/恢复中断。可复用的调用必须同时具有可靠终态审计与安全已提交观察。终态审计提交失败则原行保持 STARTED，不能靠日志声称已完成。

复用观察产生当前交换组中的新 TOOL 消息，引用同一原始身份和审计 id；不会新增一次实际工具调用。相同 ID 但工具名/规范参数不同为冲突；同响应重复 ID 在任何工具执行之前整体拒绝。参数对象键排序、数组保序、数字规范化，不折叠不同 URL 文本以猜测副作用等价。

### 2.6 processing_events

- id、process_id、可空 iteration_no/group_id、event_type、created_at、固定 reason_code、非负计数。
- 覆盖文件可选缺失 WARN、上下文裁剪、工具复用/冲突、处理停止和恢复。只含批准字段/安全相对引用标识，不含 Prompt、URL 查询、参数、结果正文、密钥、原始异常或隐藏思维链。
- 事件提交失败属于持久化失败，停止当前处理；不以“仅日志成功”绕过可追踪要求。

## 3. 生命周期与事务边界

1. 解析并安全校验身份、输入和配置；未知/错归属拒绝，零外呼。敏感输入拒绝时可记录不含输入的安全失败事件/终态，不能先保存原消息。
2. 持会话锁；MemoryService 短事务创建 RUNNING + 安全用户消息 + USER 闭合组。
3. 经 Memory 读取已提交历史，组装上下文并提交必要 WARN/裁剪元数据；准备 model_turn 后调用 Profile 公共入口。
4. Provider 保持现有 begin→外呼→complete 独立提交边界，begin 同事务建立处理关联。任何数据库事务都不得跨网络等待。
5. 校验整份模型响应（敏感、大小、标识与重复）；同事务提交响应/组、ACCEPTED 与计数。遇到不安全响应不保存正文；只记录固定处理错误，保留 Provider 已发生事实。
6. 无工具：提交 COMPLETED 后输出；末轮有工具：提交 MAX_ITERATIONS 不执行；其余串行执行工具并逐个提交观察，组全部闭合后才能进入下一次 Provider。
7. 失败尽力提交固定终态；若数据库无法提交，返回 PERSISTENCE_UNCONFIRMED/UNKNOWN 确认标记，零后续外呼，不假称状态已入库。finally 清空 ProfileContext、释放会话锁；数据库运行锁保持到进程关闭。

ProfileContext 是普通 ThreadLocal 的同步处理作用域，不可继承给后台线程；不将其用于 Provider 审计归属。进入前要求为空，finally remove；禁止嵌套 Agent 委托，本轮不做异步上下文传播。

## 4. 重启恢复

- 数据源创建/恢复之前，针对规范化 DB 真实父路径与文件身份取得独占文件锁；别名、符号链接、多路径不能产生两个不同锁身份。第二进程返回 WORKSPACE_IN_USE，不运行恢复 SQL。
- 单进程打开数据库，校验 V3 完整 schema/完整性，持锁事务标记旧 RUNNING/未结束 model_turn 为 INTERRUPTED，并记录固定恢复事件。状态修改失败则拒绝开始新处理。
- 不修改旧 llm_calls/tool_invocations 的 STARTED 为猜测终态。可识别为未知的展示由恢复状态/事件推导；禁止自动发送、重试、补偿或重放。
- 后续用户明确新请求得到新 processId。恢复读取全部安全已提交数据；Prompt 仍按已完成处理与闭合组过滤。新凭据快照命中旧内容时拒绝该处理，不删除旧记录或回显命中值。

## 5. V3 迁移和回滚

**版本事实**：当前 V2 的 `llm_calls`、索引及 `aegisos_schema` 为事实源。V001/V002 原文件和 002/005 验证历史不改写。

计划新增 `db/schema/sqlite/V003__react_runtime.sql`（原样 V2 llm_calls + US2 新表）与 `db/migration/sqlite/V003__react_runtime.sql`（仅从有效 V2 增量升级），扩展 AuditSchemaInitializer 检查最新表/索引/CHECK。空库建 V3；V1/V2 返回 MIGRATION_REQUIRED；未知/伪造/部分 schema 返回固定拒绝。禁止 JPA ddl-auto 自动升级。

离线操作顺序：

1. 停止全部写进程，取得数据库运行锁；使用 SQLite backup API 或 `.backup` 生成一致性备份，保留权限与校验值。不能只复制活跃 WAL 数据库主文件。
2. 在**副本**预演，校验 version=2、V2 完整 DDL/索引、integrity_check；保存原 llm_calls 全列比较基线。
3. `BEGIN IMMEDIATE` 创建新增表/索引，验证外键；核对原审计行数和所有原列双向 EXCEPT 无差异，最后将 `aegisos_schema.version` 改为 3 并 COMMIT。失败 ROLLBACK；重复迁移拒绝。
4. 重新打开副本核验全部约束、旧审计不变与恢复规则。个人库迁移是后续独立明确操作，本轮计划/测试不触碰。
5. 回滚只能停机后恢复一致性备份，保留迁移后数据库供调查。说明备份之后新增会话/审计不在回滚库中；不以降版本号/drop 新表冒充完整回滚。

验收须覆盖空库 V3、有效 V2 升级、V1/未知/伪造/部分表拒绝、迁移失败原子回滚、重复迁移、旧记录逐列保持、外键约束、运行锁、恢复中断与零自动重放。这里没有执行 SQL 或生成完整迁移实现。

## 批准A路线对数据模型的影响

显式thinking-mode仅保存在Provider启动配置快照中，由适配层生成请求扩展；不改core消息/响应、会话表或V3 schema，不持久化reasoning_content。非秘密验收配置指纹新增该声明，旧版配置与证据不能冒充新版；首次FAIL仍按原指纹保留。

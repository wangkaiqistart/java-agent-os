# US2 课程来源与验收追溯

**日期**：2026-10-22

**规格**：[spec.md](spec.md)

**课程来源**：《第17节：ReAct 原理解析、实现与代码讲解》PDF，共 7 页。以下页码按 PDF 从 1 开始计数，正文、图示、示例代码和跨页验收表已逐页核对。外部课程文件未复制进 Git；这里保留来源条目、项目取舍和验收映射，不把课程中的命令或代码视为直接执行指令。

**来源 SHA-256**：`2917fd156f06b91443c383a496ed4864ed0328ca560b184b44cb266c28ae2a3a`

**状态说明**：实现与离线验证已完成，工程门禁通过，见 [validation.md](validation.md)。本表保留设计追溯并在第5节登记当前实际证据；首次A14真实天气FAIL保留；批准A路线后经新授权实际复验PASS，141/141项完成。真实证据独立于离线和旧Provider冒烟。

## 1. PDF 条目到需求与验收

“故事 X / 场景 Y”对应 spec.md 的用户故事和验收场景。

| 条目 | PDF 页码 / 来源内容 | 需求 | 成功标准 | 验收场景 | 计划阶段应落实的验证 |
| --- | --- | --- | --- | --- | --- |
| PDF17-01 | 第 1～3 页：Reason / Act / Observe；无工具返回；循环由项目掌控 | FR-001、002、004、009 | SC-001、002 | 故事 1/1；故事 2/1；故事 3/4 | 固定模型响应序列，验证无工具一轮结束、工具回填后再调模型、文本与意图同时保留、零 SDK 自动执行 |
| PDF17-02 | 第 1、3、6 页：默认最大 10 轮；持续工具请求强制停止 | FR-007、012 | SC-003 | 故事 3/1、2 | 验证上限 1、2、10；恰好 N 次模型逻辑请求；第 N 次工具响应零执行，记录相对课程示例的已批准差异 |
| PDF17-03 | 第 2～4 页：每轮响应与工具结果追加 Session，带 sessionId 关联 | FR-003、006、010、014、034 | SC-004、006 | 故事 1/2、3；故事 2/1、2；故事 3/3 | 查询模型/工具记录及消息顺序，重开存储恢复；区分会话、处理、requestId、attemptNo 和工具标识 |
| PDF17-04 | 第 4～5 页：Prompt 四部分、系统指令末尾当前时间、默认保留 20 轮 | FR-011、015、016、027、030、031 | SC-007、008 | 故事 4/1、4、5、6 | 固定时钟/时区，校验组成顺序、长期记忆关闭/替身注入、完整历史组裁剪和超限零外呼 |
| PDF17-05 | 第 4～5 页：ToolExecutor 唯一执行点，Sandbox 检查与工具持久审计 | FR-005、008、020～025、032 | SC-002、004 | 故事 2/1～6 | 成功、拒绝、未知工具、失败、超时、重复标识、审计不可用；工具表 success/error_message 存在且一致，重开可查 |
| PDF17-06 | 第 4～6 页：AgentService 统一编排，ProfileContext 设置与 finally 清理 | FR-001、026、028 | SC-009 | 故事 1/4、5；故事 3/6 | 正常、模型异常、工具审计异常、会话保存异常、同线程切换 Agent、不同会话并发；每次结束后上下文为空 |
| PDF17-07 | 第 5～6 页：ContextLoader 每次读 Bootstrap 与 SKILL.md；缺失错误/WARN | FR-017～019 | SC-007、008 | 故事 4/2、3、5、7 | 临时文件原地修改后下次构建生效；显式缺失报错、可选默认 Bootstrap 缺失警告、软连接越界拒绝、正文超限 |
| PDF17-08 | 第 5～6 页：五组单元验收及两个关键回归 | FR-012、013、036、038 | SC-003、005、009、011 | 故事 1～4 全部场景 | 对应课程 ReActLoopTest、PromptBuilderTest、ToolExecutorTest、AgentServiceTest、ContextLoaderTest 五组行为；测试类归属由计划确定，默认不访问真实服务 |
| PDF17-09 | 第 7 页：真模型 + http_get 天气对话，拿到结果再给建议 | FR-021～025、035～038 | SC-010、011 | 故事 5/2～4 | 显式启用的真实验收，真实模型工具意图、真实天气结果、非空建议、同会话追问、审计重开；缺条件/跳过不得 PASS |
| PDF17-10 | 第 5、7 页：暂不做并行、委托、流式、压缩；人工确认自实现循环 | FR-001、005、009、011、013 | SC-002、005、011 | 故事 2/2；故事 3/1；故事 4/4 | 代码评审确认项目掌控循环、串行工具及统一记忆边界；测试和真实验收不能代替架构评审 |

## 2. 项目补充约束与课程差异

这些要求来自既有用户决定、宪法、US1/005 或项目 US2 指引。不得宣称全部都由课程原文直接要求。

| 项目来源 | 需求 | 验收映射 / 处置 |
| --- | --- | --- |
| 2026-10-19 五项澄清：工具失败回填、末轮不执行工具、历史裁剪、串行工具、跨重启恢复 | FR-003、005～008、011、014 | 故事 1/3；故事 2/2、3；故事 3/1、2；故事 4/4；原决定全部保留 |
| 课程末轮工具示例与已确认上限规则不同 | FR-007 | SC-003：最后一次响应含工具时只记录并返回达到上限；不会照抄示例多执行一批工具 |
| 005 Profile 启动快照、技能来自目录，Provider 新入口只验证追踪值格式 | FR-014、017、019、034 | 仅文件正文逐次重读，配置/绑定关系重启生效；US2 增加真实会话存在性和归属校验，不改 US1 兼容入口 |
| 宪法 Memory 统一门面，US3 尚未交付长期后端 | FR-027 | 故事 4/6：本轮会话能力经门面访问，长期记忆允许空能力/替身；不得在循环直接读具体后端 |
| 当前 US2 历史恢复决定与副作用安全 | FR-028、029、032 | 故事 3/5、6：恢复已提交历史但不自动重放未确认工具；同会话并发拒绝、同标识去重 |
| 2026-10-22 用户 Q1=A：最小真实工具及显式天气验收 | FR-021～025、036～038 | 故事 2/6、故事 5/3、4；http_get/Sandbox/持久审计本轮交付，其他工具生态后续交付 |
| 2026-10-22 用户 Q2=A：每次注入绑定 Skill 正文 | FR-015、017～019、031 | 故事 4/1～3、5、7；替代本功能范围内的“元数据注入、正文按需读取”旧设计 |
| 项目 AI 实施指引第 4.2 节的 CLI 初始化/对话 | FR-035 | 故事 5/1、2：最小 init/chat，复用统一入口；不包含 Web、通知或定时调度 |
| 宪法及 US1/005 的敏感信息保护、前置审计、有限重试；本轮确认已知密钥拒绝 | FR-010、023～025、033、034、038 | 故事 1/4、故事 2/5、故事 3/3；敏感哨兵一经发现即拒绝且零泄露、审计失败零新增副作用、无额外重试；不覆盖旧验证历史 |

## 3. 验收分层与后续产物

1. **规格质量**：检查需求可测、范围清晰、差异有依据和场景可追溯；见 [质量清单](checklists/requirements.md)。不运行模型，不宣称实现通过。
2. **本地功能验收**：五组课程行为、会话跨重启与 CLI/存储集成；使用合成数据、临时文件和本机替身。课程的纯单元测试不能替代新增持久化恢复与 CLI 的集成验证。
3. **工程门禁**：每故事聚焦测试、项目 Maven test/package、质量/秘密/依赖检查；以本次实施证据为准，不沿用历史 PASS 代替当前结果。
4. **真实验收**：仅通过显式开关和受控条件运行 DeepSeek 与批准的天气查询，记录逻辑模型轮数、物理发送、工具调用和审计关联；失败、未发现、跳过、陈旧报告明确不是 PASS。
5. **人工架构确认**：自实现循环、SDK 零自动工具执行、统一 Memory 门面、工具唯一 Sandbox 执行边界以及正文/配置不同生效时机。

`$speckit-plan` 于 2026-10-22 将上述映射落实为设计，见下表；`$speckit-tasks` 生成原135项[实施任务](tasks.md)，批准A路线后追加6项，总计141项。本轮实现已覆盖离线故事与默认关闭的真实验收程序，任务仅按执行证据勾选；后续一次已授权真实运行实际2次模型发送/1次天气GET，结果FAIL。

## 4. Phase 1 设计与计划测试入口

| 课程条目 | 设计产物 | 计划验收组 |
| --- | --- | --- |
| PDF17-01 | [Agent/循环契约](contracts/agent-memory-provider.md) | A01、A03、A07、A15 |
| PDF17-02 | [循环停止边界](contracts/agent-memory-provider.md) | A06 |
| PDF17-03 | [数据模型/事务/恢复](data-model.md)、[Provider关联](contracts/agent-memory-provider.md) | A01、A02、A04、A07 |
| PDF17-04 | [上下文/容量](contracts/context.md)、[YAML配置](contracts/configuration-cli.md) | A08、A10 |
| PDF17-05 | [工具/Sandbox/审计](contracts/tools-sandbox.md)、[工具数据模型](data-model.md) | A03、A04、A05 |
| PDF17-06 | [统一入口与上下文清理](contracts/agent-memory-provider.md) | A02 |
| PDF17-07 | [来源映射及逐次文件读取](contracts/context.md) | A09、A11 |
| PDF17-08 | [完整验收矩阵](contracts/acceptance.md)、[验证步骤](quickstart.md) | A01～A13 |
| PDF17-09 | [CLI配置](contracts/configuration-cli.md)、[独立真实天气验收](contracts/acceptance.md) | A12、A14 |
| PDF17-10 | [实施计划/模块边界](plan.md)、[研究取舍](research.md) | A15 |

A01～A16定义及FR-001～039、SC-001～012的完整映射见 [验收契约](contracts/acceptance.md)。当前各项验收结果见下表；新版真实天气PASS，未进行生产部署。

## 5. 实施后的实际证据

2026-10-22用户批准的A路线是项目协议补充，不宣称课程原文要求：FR-039/SC-012→A16→T136～T141，显式disabled的配置/快照/真实wire请求与指纹离线验证；thinking工具续轮不在本轮范围。原PDF17-09的真实FAIL保留；用户已新授权一次，新版真实PASS见validation.md。

本表与第1节的PDF→FR/SC→场景映射共同使用，测试名对应实际源码；各阶段日志和测试发现数见 [validation.md](validation.md)，架构结论见 [architecture-review.md](architecture-review.md)。

| 课程条目 | 实际任务与测试入口 | 当前结果与限制 |
| --- | --- | --- |
| PDF17-01 | T032～T048、T064～T072；ReActLoopTest、AgentServiceTest、ProviderToolLoopIntegrationTest | PASS（离线）；自实现循环，文本/工具观察闭环与SDK零自动执行 |
| PDF17-02 | T073～T086；ReActLoopTest、IterationBoundaryIntegrationTest | PASS（离线）；N=1/2/10，末轮零工具、无N+1 |
| PDF17-03 | T018～T025、T038～T048、T064～T086；SessionPersistenceTest、ProcessingCorrelationTest、PipelineCrashIntegrationTest、RuntimeRecoveryTest | PASS（临时SQLite）；跨重开、审计关联、故障恢复不重放；未操作个人库 |
| PDF17-04 | T087～T105；PromptBuilderTest、MemoryServiceTest、ContextHistoryQueryTest、ContextProjectionIntegrationTest | PASS（离线）；统一门面/长期默认空、固定Clock顺序、完整组裁剪及wire投影 |
| PDF17-05 | T049～T072；ToolExecutorTest、ToolDeduplicationTest、ToolAuditPersistenceTest、HttpGetToolTest、SandboxPolicyTest、ToolFailureBoundaryTest | PASS（离线/受控回环）；持久前置门槛、授权/地址/容量/时限、零隐藏重发 |
| PDF17-06 | T032～T048、T073～T086；AgentServiceTest、SessionConcurrencyTest、ProcessingInterruptionTest | PASS；统一入口、同会话互斥、finally清理，不依赖ThreadLocal猜归属 |
| PDF17-07 | T026～T031、T087～T105；ContextLoaderTest、ProfileSourceMappingTest、RuntimeSnapshotTest、ContextCapacityBoundaryTest | PASS；Linux强制SDS正向读取，macOS明确拒绝；正文重读与配置快照分离 |
| PDF17-08 | T005～T125、T129～T130；五组课程行为、旧Provider回归、CliRuntimeWiringTest、ReactSmokeEvidenceTest、Python验证器9项；增量T136～T141 | 离线PASS；macOS/Linux各87套件/297项零失败错误跳过；完整工程安全结果单独记录 |
| PDF17-09 | T106～T125的CLI/harness、T133新授权A路线执行；CliCommandTest、CliConversationTest、ReactWeatherSmokeIT、verify-react-smoke.py | CLI与harness离线PASS；**新版真实天气A14 PASS**（3模型发送/1GET、零重试、同会话两处理完成），Maven/独立IT/verifier及重开审计均通过；首次FAIL保留 |
| PDF17-10 | T127架构复核、T129静态分析；architecture-review.md | PASS（架构）；串行工具、自控循环/Memory/Sandbox；无并行、委托、流式或自动压缩 |

FR-001～039与SC-001～012按验收契约逐项映射到A01～A16，再经上表/任务清单定位实际测试与validation.md。包含真实范围的FR-013/021/036～038、SC-010/011已由新版A14完成，FR-039/SC-012由A16离线及新版实跑共同留证。T133～T135现已完成，总计141/141；首次失败及原指纹保留。仍不支持thinking工具续轮，未操作个人库或宣称生产部署通过。

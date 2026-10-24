# US2 架构与关键边界复核

本记录对应 T127；基于当前源码逐项复核，不等同于真实模型验收或生产部署。九模块/Java21/Boot4.1.1/SpringAI2.0.1 保持，未修改002/005任务与验证历史。

| 边界 | 当前实现与核验入口 | 结论 |
| --- | --- | --- |
| 自控 ReAct | core AgentService/ReActLoop；循环本身不依赖 SDK/Spring/JPA；接受且提交响应才计数，末轮工具0、无下一轮 | PASS；ReActLoopTest/IterationBoundaryIntegrationTest |
| 显式 Provider / 零自动工具 | 四参 ProfileProviderService 显式 session/process/iteration；原 DefaultProviderService 负责路由/有限重试/审计；SDK只返回工具意图 | PASS；ProcessingCorrelationTest 与原 Provider/mapper 回归 |
| Memory 门面 | 循环只访问 MemoryService；DefaultMemoryService 承接 SessionStore 与 LongTermMemoryStore，默认长期空 | PASS；MemoryServiceTest/LongTermContextTest |
| 工具唯一 Sandbox | DefaultToolExecutor 解析/身份/去重/授权，统一 Sandbox 发许可，持久 begin 后才调用生产 HttpGetTool | PASS；ToolExecutorTest/ToolFailureBoundaryTest |
| 事务与恢复 | prepare/begin/accept/complete/观察/finish 都是独立短事务；无跨网络事务。恢复先持锁和校验V3，遗留中断不重放 | PASS；RuntimeRecoveryTest/RuntimeStorageLifecycleTest/PipelineCrashIntegrationTest |
| 审计一致性 | Provider link 与 begin/reject 同事务；工具终态与可靠观察核对，只有两者提交才复用；complete失败不伪装成功 | PASS；ProcessingCorrelationTest/ToolAuditPersistenceTest |
| 同步作用域清理 | ProfileContext 使用普通 ThreadLocal，AgentService finally 释放；无 InheritableThreadLocal，不用它推断审计归属 | PASS；AgentServiceTest/SessionConcurrencyTest |
| 原始与 wire 身份 | 去重保存原ID/规范参数；发送按process/组/调用序号生成wire ID，ASSISTANT/TOOL配对投影，持久历史不改 | PASS；PromptProjectionContractTest/ToolDeduplicationTest |
| 敏感内容 | 受控启动凭据与客户端同值快照，各输入/历史/上下文/模型/工具/CLI检查；命中拒绝，不替换后继续。真实已发生审计保留 | PASS；SensitiveContentGuardTest/敏感边界集成/CLI测试 |
| 文件与来源 | 逻辑名映射实际目录，绑定启动快照；正文逐轮安全重读。SDS/NOFOLLOW/批准根/身份/UTF8/容量检查 | PASS；macOS明确拒绝，Linux强制SDS正向；无弱化fallback |
| 网络目标与资源 | exact HTTPS目标/路径/query键，一次DNS检查全地址并固定首IP；关redirect/retry/proxy/auth/cookie/compression；主动截止与limit+1读取 | PASS；SandboxPolicyTest/HttpGetToolTest/HttpGetSecurityIntegrationTest |
| CLI / 配置 | init在Spring前，CREATE_NEW，不覆盖；chat专用non-Web显式source、唯一workspace YAML、相对audit路径锚定；stdin单次、stdout七字段 | PASS；CliCommandTest/CliRuntimeWiringTest，Linux真实JAR strace探针 |
| 真实验收防误放行 | 独立profile/配置/预算，模型/GET前扣额度；天气事实+最新已转发观察、重开审计；本轮指纹/时间/XML/JSON/只读DB verifier | 离线PASS；首次A14 FAIL被verifier拒绝；批准A路线新授权实跑A14 PASS，3模型发送/1GET，事实与重开核验全部通过 |

## 复核中修订并补证

- 保留平台强契约：macOS 不提供 SDS 时返回 CONTEXT_FILE_UNAVAILABLE，Linux 测试强制SDS属性；没有以macOS拒绝路径替代正向验收。
- 补充 192.88.99.0/24 退役中继与 3fff::/20 文档地址保留段的拒绝测试，先 RED 暴露公网分类遗漏，再修复。
- 增加恢复提交拒绝的事务测试：在已通过schema检查的活动临时运行时创建RUNNING/PREPARED，注入拒绝提交且回滚的事务管理器，确认固定 PERSISTENCE_UNCONFIRMED、原状态/事件全回滚、外呼0。原额外trigger阻断启动测试继续保留，但不再拿它冒充独立提交失败证据。
- init 的默认 Bootstrap 示例放在 `.aegisos/AGENTS.md`、SOUL.md、USER.md，和 ContextLoader 的默认读取根一致。
- Python 验证器拒绝重复证据键，并关闭每个 SQLite 只读连接；不从报告中读取凭据或正文。配置指纹排除工作区路径与凭据值，只覆盖非秘密声明。
- 固定 Clock 的真实 SQLite 反例发现历史原按 started_at/随机 UUID 排序会打乱同一时刻完成的处理；改为按各处理首条 USER 消息的 sequence_no 选取最近窗口并恢复正序。ContextHistoryQueryTest 覆盖同一时间戳下的顺序和窗口，不再依赖 UUID 或墙上时钟碰巧递增。
- SandboxProperties 的 paths/queryKeys 及目标列表固定为不可变快照；反例先验证外部列表修改不会改变授权，再落实防御性复制。ContextHistory 同样保护嵌套只读视图。
- SpotBugs 首轮发现 core、storage、CLI、boot 的内部表示/可空引用问题；通过行为端口、只读快照、当前事务 EntityManager Supplier 和明确前置条件修复，未抑制规则。最终 macOS/Linux 全工程静态检查均为 0 bugs / 0 errors。
- 高负载并行构建曾使两项正向 Provider 测试触发共用 120ms 故障夹具超时；仅为这两项正向测试显式设置 5s，保留所有超时/重试用例的 120ms 断言。串行完整 test、clean package、quality 随后通过。

## 范围与注释检查

关键 Java 类/复杂事务/安全边界已有中文说明，重点解释“为什么先提交”“为什么拒绝/停止”“为什么不重试/不drain”“SDS 与快照关系”；配置模板使用 YAML 与中文注释。公共契约不包含 Provider/JPA 实现类。无测试用私网 YAML 开关、密钥命令参数、自动个人库迁移或恢复的 Provider Web 测试接口。

长期 Memory 后端、其他8个工具、MCP/Plugin、通知/调度、Web业务API未实现。Sandbox 是应用层限制，不宣称OS强隔离；敏感检测不声称识别任意未知编码；上下文预算限定发送的逻辑UTF8 JSON，不是模型token或进程堆大小保证。测试窗口/配置有硬上限，尚无吞吐/堆内存SLA或多节点能力承诺。

最终质量/秘密/依赖门禁以 validation.md 的本轮结果为准；任何未通过门禁都不能通过本记录放行真实验收。

## 真实执行后发现的兼容缺口

一次已授权真实运行中，模型首轮/天气工具成功，第二次模型请求INVALID_REQUEST。首次失败时源码确认公共消息/响应及OpenAI mapper未保留reasoning_content，且没有显式thinking配置；DeepSeek当前官方文档默认启用thinking，工具续轮要求回传该字段。这是先前OpenAI标准协议离线样本未覆盖的厂商扩展，不能因九模块/循环边界检查通过而声称兼容。具体错误字段仍缺原始厂商证据，不能推定唯一根因。完整记录见validation.md，修订路线见deepseek-compatibility-gap.md；不静默切换模式或降低验收。

## 用户批准A路线后的增量复核

- ProviderThinkingMode限定UNSPECIFIED/DISABLED；保留旧ProviderDefinition构造入口，缺省请求不增加扩展。模式随Provider注册固定，不根据名称/模型/端点推断。非法标量在凭据解析前成为不可用项，严格CLI YAML另拒绝null/结构值。
- 生产OpenAiMessageMapper每次请求生成thinking.type=disabled，适配器只保存不可变选项；未让harness特判网络包，未引入SDK自动工具/重试或循环外层重试。ProviderThinkingModeTest使用真实loopback HTTP证明首轮/工具续轮及运行中修改配置不改变快照。
- 专用Java/Python配置强制disabled且参与指纹；ReactWeatherSmokeIT仅将已校验声明传入生产ProviderProperties。旧配置不能当新版通过；首次FAIL按原配置/原指纹独立保留。
- 不改公共core消息/V3schema，不存储reasoning_content，不承诺thinking工具续轮。关键新增边界已有中文注释。macOS/Linux完整质量、秘密/依赖门禁及JAR探针已PASS，具体数量/覆盖限制见validation.md。新版真实验收已单独执行且PASS，3模型发送/1GET、零重试；本复核不替代其XML/JSON/只读审计证据。

## 三项代码评审修复复核（T142～T150）

以上记录对应原141项及真实验收历史。本次基于修复前快照追加审查，没有重新运行真实验收、改动依赖基线或迁移个人库。

| 原评审问题 | 当前修复与回归入口 |
| --- | --- |
| 转义敏感字段失去键值关系 | JsonStringValues 保留直接相邻解码字段/标量；DefaultSensitiveContentGuard 按完整结构化值判断占位符。SensitiveContentGuardTest 与 Linux SensitiveOutputBoundaryTest 证明标准转义、占位前缀和引号不能绕过，模型工具意图及工具审计不入库 |
| SQLite inode 锁在 JDBC 关闭后失效 | RuntimeDatabaseLock 仅持独立 sidecar，JVM 路径先认领；nlink不可验证/多硬链接拒绝，规范目标 .lock 后缀保留。RuntimeDatabaseLockTest/RuntimeStorageLifecycleTest 验证真实物理连接轮换、独立进程竞争、软链接/硬链接和锁文件角色冲突，关闭后可重开 |
| Profile 超范围仍注册 | ProfileLoader 在发布 Profile/AgentSource 前校验 ProfileRuntimePolicy；ProfileLoaderTest/SourceMapping/RuntimeRegistrationTest 覆盖缺省/端点/坏项隔离及零会话、处理、USER、模型请求 |

复审首轮进一步发现敏感占位前缀和数据库/锁文件角色冲突，均先补回归确认行为失败再修复；原候选、RED/GREEN 与最终独立复审结果分别归档，不删改旧评审。最后结果与测试发现数以 validation.md 的增量记录为准。

最终复审（2026-10-24）已完成：21/21修复文件经分组审查和独立评分，未发现剩余新增缺陷。最后macOS三项Maven门禁及Linux强制SDS质量验证各88套件/334测试零失败/错误/跳过，秘密扫描0发现。原141项历史保留，追加后150/150；详细证据及局限见validation末尾，未commit/push或新增真实外呼。

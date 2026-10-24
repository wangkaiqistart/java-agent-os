# US2 代码变更、重点 Review 与验证指南

本文面向准备阅读、复审或接手 US2 的开发者，覆盖 ReAct 主体实现、显式关闭 thinking 的 Provider 兼容增量，以及随后三项 review 修复。事实源为当前源码、[规格](../../specs/003-react-loop/spec.md)、[任务](../../specs/003-react-loop/tasks.md)与[验证记录](../../specs/003-react-loop/validation.md)。

截至 2026-10-24，原 141 项任务与后续 T142～T150 共 **150/150** 完成。本版本用于 `codex/us2-react-loop` 分支的 US2 归档；提交与推送由用户在完成收尾后单独授权。下文“未提交/未推送”保留为各验证阶段当时的状态，归档过程见验证记录末尾。代码通过验证不等于已部署或获得下一次真实调用授权。

## 1. 代码变更与阅读入口

US2 将 US1 的一次 Provider 调用扩展为可持久化的 Agent 处理：先创建绑定 Profile 的会话，接收用户消息，循环请求模型，串行执行受控工具，保存观察并继续推理，最终完成或明确停止。

| 模块 | US2 主要变化 | 建议先读的文件 |
| --- | --- | --- |
| aegisos-core | 公共 Agent、Memory、Tool 契约；自实现循环、Prompt/上下文、敏感检查 | [AgentService](../../aegisos-core/src/main/java/com/aegisos/core/agent/AgentService.java)、[ReActLoop](../../aegisos-core/src/main/java/com/aegisos/core/agent/ReActLoop.java)、[PromptBuilder](../../aegisos-core/src/main/java/com/aegisos/core/context/PromptBuilder.java) |
| aegisos-memory | 统一 MemoryService，承接会话与长期记忆端口；生产长期记忆当前为空 | [DefaultMemoryService](../../aegisos-memory/src/main/java/com/aegisos/memory/DefaultMemoryService.java)、[EmptyLongTermMemoryStore](../../aegisos-memory/src/main/java/com/aegisos/memory/EmptyLongTermMemoryStore.java) |
| aegisos-provider | 显式关联 session/process/iteration，保留原有限重试及前置审计；显式 thinking-mode 配置 | [DefaultProviderService](../../aegisos-provider/src/main/java/com/aegisos/provider/service/DefaultProviderService.java)、[OpenAiMessageMapper](../../aegisos-provider/src/main/java/com/aegisos/provider/adapter/OpenAiMessageMapper.java) |
| aegisos-tool | ToolExecutor → Sandbox → 持久审计 → http_get；去重、精确网络许可与资源上限 | [DefaultToolExecutor](../../aegisos-tool/src/main/java/com/aegisos/tool/DefaultToolExecutor.java)、[WhitelistSandbox](../../aegisos-tool/src/main/java/com/aegisos/tool/WhitelistSandbox.java)、[ControlledHttpTransport](../../aegisos-tool/src/main/java/com/aegisos/tool/ControlledHttpTransport.java) |
| aegisos-storage | V3 会话/处理/轮次/交换/审计关联；短事务、启动恢复、数据源生命周期锁及离线迁移 | [JpaSessionStore](../../aegisos-storage/src/main/java/com/aegisos/storage/session/JpaSessionStore.java)、[RuntimeRecoveryService](../../aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeRecoveryService.java)、[RuntimeDatabaseLock](../../aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeDatabaseLock.java) |
| aegisos-cli | init 初始化新工作区；chat 读取一次 stdin，支持显式 session 续聊 | [InitCommand](../../aegisos-cli/src/main/java/com/aegisos/cli/InitCommand.java)、[ChatCommand](../../aegisos-cli/src/main/java/com/aegisos/cli/ChatCommand.java) |
| aegisos-channel-cli | CLI 输入输出适配，stdout 为固定安全 JSON，诊断到 stderr | [CliChannel](../../aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/CliChannel.java) |
| aegisos-boot | 显式生产装配、YAML/运行策略校验、凭据快照、生产链路集成测试与默认关闭的真实验收入口 | [AgentRuntimeConfiguration](../../aegisos-boot/src/main/java/com/aegisos/boot/config/AgentRuntimeConfiguration.java)、[ProfileLoader](../../aegisos-boot/src/main/java/com/aegisos/boot/config/ProfileLoader.java) |
| aegisos-web | 保留基础 Web/健康/监控入口；本轮没有新增 Agent 业务 REST API | [运行与边界说明](us2-runtime.md) |

按 `AgentRuntimeConfiguration → AgentService → ReActLoop → PromptBuilder/MemoryService → Provider/ToolExecutor → JpaSessionStore` 阅读。先弄清一次处理的控制流，再追事务、错误与资源释放；不要一开始逐个读全部实体。

## 2. 一次请求中要看懂的状态变化

1. `createSession(profileName)` 取得运行时创建、持久绑定 Profile 的会话；`process` 明确传入会话和 Profile，错误归属不会静默新建或换 Agent。
2. `AgentService` 完成输入/归属检查，非等待地获取会话锁，进入 ProfileContext；开始处理时可靠保存 USER。
3. `PromptBuilder` 读取当前指令正文，经 Memory 门面取上下文并应用 UTF-8 逻辑 JSON 预算；裁剪不改写原历史。
4. 先提交 PREPARED，再进入 Provider 的前置审计门槛和显式路由。Provider 内部重试仍由原服务负责，循环不包一层隐藏重试。
5. 模型响应经过敏感内容、身份、重复调用标识和容量检查，再由 Memory 接受并提交；只有此时才增加已接受轮次。
6. 无工具即完成；有工具且已到末轮则保存 OPEN 意图后停止，工具执行次数为零。其他轮次按返回顺序逐个执行，观察可靠提交后进入下一轮。
7. finally 清理作用域和会话锁。重启把遗留 RUNNING 标记为 INTERRUPTED，保留旧审计/OPEN 组，不重放模型或工具。

重点区分 `sessionId`（会话）、`processId`（一次用户处理）、`requestId`（一次 Provider 逻辑请求）及 `attemptNo`（请求内尝试）。一次处理可以有多轮，每轮 Provider 还可能按配置有限重试；这些计数不能混用。

## 3. 重点 Review 清单

| 审查位置 | 要追到的行为与反例 | 主要测试入口 |
| --- | --- | --- |
| 循环与停止 | 接受且提交后计数；N=1/2 等边界无 N+1 模型请求；末轮工具零执行；失败不隐藏重试 | ReActLoopTest、IterationBoundaryIntegrationTest、ProviderFailureLoopTest |
| Memory 与事务 | 循环只经 Memory 门面；审计可靠提交前零外呼；响应/观察保存失败停止；网络等待不跨长事务 | MemoryServiceTest、SessionSaveFailureTest、ToolFailureBoundaryTest、PipelineCrashIntegrationTest |
| 会话/身份/恢复 | 会话绑定 Profile、并发拒绝、作用域最终清空；重启恢复不重放；模型/工具审计逐条关联 | SessionConcurrencyTest、ProfileContextLifecycleTest、RuntimeRecoveryTest、ProcessingCorrelationTest |
| Prompt 与文件 | 启动配置/技能绑定快照，正文逐次安全重读；批准根、软链接身份、SDS 与 UTF-8 上限；完整工具交换裁剪 | ContextLoaderTest、ContextPathReplacementTest、PromptProjectionContractTest、ContextCapacityBoundaryTest |
| 工具与网络 | 每个工具经 Sandbox/前置持久审计；同 ID 仅可靠提交后复用；拒绝私网/混合 DNS、重定向、超限和超时 | ToolExecutorTest、ToolDeduplicationTest、HttpGetSecurityIntegrationTest、SandboxPolicyTest |
| Provider 兼容 | thinking 仅显式 disabled 生效，首轮/工具续轮均由生产 mapper 生成；缺省不猜测，原重试/审计不回退 | ProviderThinkingModeTest、ProviderRuntimeRegressionTest |
| CLI 与装配 | init 不覆盖、不建库、不联网；chat 真正接入生产 AgentService；安全 JSON 与退出码一致 | CliCommandTest、CliRuntimeWiringTest、CliConversationTest |
| 后续三项修复 | 检查顺序和零副作用必须成立；不能只看错误字符串或 DTO 的脱敏输出 | 下节的三组回归 |

发现问题时写清“触发输入 → 实际行为 → 违反的规格/边界 → 证据位置 → 修复与回归”。静态 review 与测试执行分别给结论；测试通过不能替代事务/模块/安全边界审查。

### 3.1 敏感 JSON：解码后仍须保留完整字段关系

原问题是分别检查解码后的字段名和值，导致转义字段名承载的实际值漏检。首轮复审还发现：重新拼回文本交给正则，会把 `null example-value`、`env:REFERENCE example-value` 等的首词误认成安全占位符。

重点读 [JsonStringValues](../../aegisos-core/src/main/java/com/aegisos/core/context/JsonStringValues.java) 与 [DefaultSensitiveContentGuard](../../aegisos-core/src/main/java/com/aegisos/core/context/DefaultSensitiveContentGuard.java)：相邻解码字段/标量关系必须保留，占位引用匹配完整值，不跨对象/数组串联字段。检查必须早于接受/持久化模型响应。

看 [SensitiveContentGuardTest](../../aegisos-core/src/test/java/com/aegisos/core/context/SensitiveContentGuardTest.java) 的拒绝与允许正例，再看 [SensitiveOutputBoundaryTest](../../aegisos-boot/src/test/java/com/aegisos/boot/react/SensitiveOutputBoundaryTest.java)：Linux 返回 SENSITIVE_CONTENT、模型意图不入库、工具执行和工具审计零条、仅保存原安全 USER。macOS 的 CONTEXT_FILE_UNAVAILABLE 不能代替这项保存前拒绝的正向证据。不承诺任意编码或字符串内嵌 JSON 的递归识别。

### 3.2 数据库锁：连接轮换和拒绝竞争者都不能释放原锁

POSIX record lock 属于进程；同一进程关闭该 inode 的其他描述符，也可能释放锁。因此不能锁 SQLite 自身来覆盖连接池生命周期，也不能在竞争失败时先打开再关闭同一 sidecar。

重点核对：仅持独立 sidecar；JVM 在打开前认领规范路径；已有 sidecar 在打开前检查普通文件/单链接属性，打开后仍复核；数据库多硬链接或无法验证 `unix:nlink` 时拒绝；规范目标的 `.lock` 后缀保留给锁文件。

看 [RuntimeDatabaseLockTest](../../aegisos-storage/src/test/java/com/aegisos/storage/runtime/RuntimeDatabaseLockTest.java) 和 [RuntimeStorageLifecycleTest](../../aegisos-storage/src/test/java/com/aegisos/storage/runtime/RuntimeStorageLifecycleTest.java)：真正淘汰 Hikari 物理连接，确认旧 SQLiteConnection 已关闭、新对象不同，再让独立 JVM 竞争。占用须退出 3，安全拒绝须退出 5；任意非零退出（例如类加载失败的 1）不算通过。硬链接 sidecar 反例须在拒绝后删除别名恢复 nlink=1，再确认原锁仍有效，避免“链接检查拒绝”掩盖“原锁已丢失”。

迁移、备份和恢复必须沿用相同 sidecar 协议，见 [迁移说明](us2-migration.md)。当前仅支持可验证上述属性的本机文件系统；不承诺抵御同等 OS 权限的恶意并发替换。

### 3.3 Profile：坏配置必须在发布前被隔离

重点读 ProfileLoader：`mapper.map → ProfileRuntimePolicy.from → AgentSource/有效 Profile 发布`。非法项不能留下非空候选被第二阶段误注册；安全名称仍参与原重复名规则，其他合法项独立注册。运行策略：max_iterations 缺省10、范围1～100；max_history_turns 缺省20、范围1～1000。

看 ProfileLoaderTest、ProfileSourceMappingTest 与 [ProfileRuntimeRegistrationTest](../../aegisos-boot/src/test/java/com/aegisos/boot/react/ProfileRuntimeRegistrationTest.java)：101/1001 在加载时得到固定 INVALID_FIELD，缺省与端点合法；坏 Profile/来源索引不存在，createSession/process 为 UNKNOWN_PROFILE，sessions/agent_processings/session_messages 三张表零行，模型请求零次。不能等 USER 保存后才发现配置错误。

## 4. 如何验证

### 4.1 先确认环境与范围

在仓库根执行 `java -version`、`mvn -version`、`git status --short --branch`，确认 Java21/Maven3.9.x，保留既有修改。正文正向验收必须在实际提供 SecureDirectoryStream 的 Linux 上执行；macOS 保留明确拒绝，不增加弱化回退。并行 review 可以只读进行，同一工作区的 Maven/clean 门禁串行运行。

### 4.2 聚焦三项修复

```bash
mvn -B -pl aegisos-boot -am \
  -Dtest=SensitiveContentGuardTest,SensitiveOutputBoundaryTest,RuntimeDatabaseLockTest,RuntimeStorageLifecycleTest,ProfileLoaderTest,ProfileSourceMappingTest,ProfileRuntimeRegistrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Linux 上为同一命令增加 `-Daegisos.test.require-secure-directory-stream=true`。在当前基线，目标七类合计64项；以后测试增加时按本次实际发现数记录。上游模块没有指定类可以跳过匹配，但必须逐一核对目标类的本轮 Surefire XML：文件新鲜、目标均发现、tests>0、failure/error/skip=0；Maven 退出0且目标零发现不能通过。

### 4.3 全工程门禁

```bash
mvn -B test
mvn -B clean package
bash scripts/check.sh quality
bash scripts/check.sh secrets
python3 scripts/verify-react-migration.py
python3 -m unittest discover -s scripts/tests -p test_verify_react_smoke.py
```

Linux 另执行：

```bash
mvn -B clean verify -Pstatic-analysis \
  -Daegisos.test.require-secure-directory-stream=true
```

每次 clean 后及时保存该命令日志和 XML 摘要，包含执行时间、命令/退出码、测试发现数和失败/错误/跳过数。多模块中途失败时，未执行模块的旧 XML 不能计入本轮通过；聚焦通过也不能冒充完整门禁通过。构建失败需诊断后验证修复，保留初次失败，不盲目重跑。

依赖新增或变更、或本轮验收计划要求时运行 `bash scripts/check.sh dependencies`；凭据仅受控注入子进程。此次三项修复没有 POM/依赖变化，没有重新运行 NVD，不将旧扫描记成此次结果。Gitleaks 缺失时按仓内安装脚本准备工具，不能因工具缺失判 PASS。

### 4.4 真实验收单独判定

本节不授权新的真实外呼。需要执行时，按 [US2 使用与严格验收指南](../../specs/003-react-loop/quickstart.md#4-严格真实天气验收显式启用) 准备本次授权、唯一 run-id、专用 YAML、受控凭据与临时库，再显式启用 real-react-smoke。

US2 已批准的天气场景是同会话两次处理、每处理最多3轮、Provider重试0、全程最多6次模型发送/2次天气GET。必须核验本轮独立 XML、天气事实与观察转发、审计逐条关联及关闭重开。零发现、跳过、缺条件或旧报告为 NOT_RUN；已执行失败为 FAIL；两者均不能称为 PASS。首次真实 FAIL 和后续新授权 PASS 保留，各自只对应各自 run-id。

## 5. 已验证结果与本说明的边界

2026-10-24最后修复代码的已执行结果如下。本说明首次整理时重新比对本地最终摘要：21份文件清单中，除增加导航的 README 外，其余20份源码/记录与最后修复清单一致。后续收尾在 validation.md 追加的记录见第7节；源码未变。本次文档和 skill 工作不重新执行 Java 门禁，也不产生真实外呼。

| 验证范围 | 已执行结果 |
| --- | --- |
| macOS test / clean package / quality | 每轮88套件、334测试，失败/错误/跳过均0；九模块打包和静态检查通过 |
| Linux 强制 SDS 的完整质量门禁 | 88套件、334测试，失败/错误/跳过均0；全部XML确认强制属性 |
| 迁移与恢复 | 临时V2备份→V3→双向旧行比较→重开→恢复PASS；未动个人库 |
| Python 验证器 | 9项通过，包含错误证据不能放行的离线回归 |
| 秘密扫描与复审 | 历史/工作目录0发现；修复增量独立复审21/21文件，无剩余可确证问题 |
| 历史真实天气验收 | 显式thinking disabled下3次模型发送/1次天气GET、同会话两处理完成；本次未重新运行 |

完整原始验证摘要与失败保留说明见 [validation.md 的修复增量](../../specs/003-react-loop/validation.md#代码评审修复增量t142t1502026-10-22)。本地忽略证据位于 `.tools/us2-review-fixes/`，不要求提交临时库、正文或凭据；正式结论保留在仓内 Markdown。此结果不代表生产部署、thinking工具续轮、完整长期 Memory、其余工具或 Web 业务 API 已交付。

## 6. 后续每次实施的交付环节

在功能实现、修复与复审收敛后，产出三部分：**代码改了什么、重点审查哪里、如何验证且实际结果是什么**。将本说明作为本项目的实例，按实际课程/功能生成对应文档，避免复制 US2 的固定数量或历史 PASS。发现新问题则追加修复任务，先回归再实现，再验证和复审；保留原任务/验证/失败记录。

项目级 [aegisos-lesson-dev](../../.agents/skills/aegisos-lesson-dev/SKILL.md) 已加入该收尾环节，可这样调用：

```text
$aegisos-lesson-dev US2 收尾：总结代码变更、重点 Review、验证方法与实际证据
```

## 7. 本次收尾复核（2026-10-24）

本次按 `aegisos-lesson-dev` 收尾模式执行，基线为 `main@75fb026` 加既有未提交 US2 实现。复用本文和 T150 独立复审，不重新实施、不新增独立代码复审、不重新运行 Java 或真实天气验收；本次只复核现有证据并追加交付记录。

| DoD | 核对依据与结论 |
| --- | --- |
| 构建与静态检查 | 最后代码的 macOS test/package/quality 和 Linux 强制 SDS quality 均为88套件/334测试、零失败/错误/跳过；属于既有执行证据 |
| 场景断言 | 复核三项修复的 RED/GREEN 记录、生产装配回归及错误证据不能放行的 Python 验证器结果；本次没有重新执行这些测试 |
| 交付物存在性 | 代码入口、迁移/运行指南、规格/任务/追溯和本文文件链接均存在；150个任务编号唯一且全部完成 |
| 前序能力回归 | 285份修复前清单中的差异均属于已记录修复；最后21份清单中的源码仍匹配，后续 README 导航和本次验证记录追加单列。US1/005的保护与全工程回归依据见 T149 |
| 六类边界 | 通过源码入口核对审计前置、敏感响应保存前检查、同步处理及 finally 清理、Spring AI 直接 model.call 不自动执行工具、YAML 配置与显式路由；三项缺陷闭合依据来自 T150，不将本次抽查称作新的全量审查 |
| 验收证明 | 原真实 FAIL 与新授权 PASS 均保留；历史天气 PASS 为同会话两处理、3次模型发送/1次天气GET。本次没有新 run-id 或外呼结论 |

重点 review 的三项结论是：**敏感 JSON 检查保留完整字段/标量关系；数据库锁独立于 SQLite 连接生命周期；非法 Profile 在注册与持久化前拒绝。** 现有独立复审覆盖21/21文件、未关闭问题0；本次仅复核该证据和对应源码，不扩大审查范围。

本次结构/摘要/任务/链接检查与秘密扫描结果追加在 [收尾验证记录](../../specs/003-react-loop/validation.md) 末尾；本地明细在 `.tools/us2-closeout/`。US2 的已批准实现和修复可作为完成产物交接；仍未 commit/push 或部署。Linux/SDS、单链接本地数据库、显式关闭 thinking 等运行边界继续适用，长期 Memory 和其余 Tool 不属于此次完成范围。

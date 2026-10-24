# AegisOS Agent Guide

## 项目定位

AegisOS 是一个基于 Java 的企业级 Agent OS 运行时底座，目标是部署在企业自己的服务器、虚拟机或 Kubernetes 环境中，为多个业务 Agent 统一提供模型调用、ReAct 推理、记忆、工具、渠道、调度、审计和 Web 接入能力。

当前仓库已建立 Java 21、Spring Boot 4.1.1、Maven 九模块骨架和工程基础设施。US-1 Provider Java 接口、OpenAI 兼容协议和 SQLite 审计代码及测试已完成，依赖安全门禁已于 2026-10-21 23:23 通过，T051 已完成；验证见 `specs/002-provider-abstraction/validation.md`；US2 已实现 ReAct、统一 Memory 门面/持久会话、Sandbox 下的 http_get 与 init/chat CLI；长期记忆默认空。当前工程门禁与 A14 真实验收状态见 specs/003-react-loop/validation.md；现有可运行能力及验证结果见 `docs/engineering/initialization.md`，开发命令见 `docs/engineering/development.md`，依赖修复与剩余风险见 `docs/engineering/dependency-security.md`。不得把长期设计当作已实现能力。

## 阅读顺序与事实源

开始任何实现、评审或测试前，先阅读以下文件：

1. `docs/IndustryResearch.md`：项目定位、行业背景、竞品边界和长期方向。
2. `docs/DemandAnalysis.md`：功能需求、非功能需求、核心与扩展范围、验收目标。
3. `docs/TechnicalSolution.md`：架构、模块、数据持久化、接口和技术决策。
4. `docs/AiProgrammingGuide.md`：Spec-Kit、User Story 和实施顺序。
5. `.specify/memory/constitution.md` 与活动功能的 spec/plan/tasks：当前治理契约和本次功能范围。
6. `docs/engineering/development.md`：实际工程配置、质量检查和 API 基础约定。

设计资料包含长期演进内容；当前实现以宪法九模块、功能规格及源码为准。遇到十四模块等历史设计差异时先核实，不借工程初始化扩模块。后续改动按受影响范围同步文档。

## 项目边界

### 核心阶段

核心阶段交付 Agent OS 的运行时内核，主要包括：

- JDK 21、Spring Boot 4.x 单体应用和 Maven 多模块工程。
- Provider 抽象，使用 Spring AI 协议客户端接入主流 LLM；首批 OpenAI 兼容适配器，Alibaba 原生协议能力按后续规格验证。
- 自实现 ReAct 循环、Prompt 构建和 Tool 执行。
- 会话记忆和长期记忆；默认使用 `.aegisos/memory/MEMORY.md`，核心阶段同时支持 SQLite 和 Mem0 后端。
- 9 个内置 Tool：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`、`notify`。
- MCP Client 及 Java `@Tool` Plugin Tool 接入。
- 应用层白名单 Sandbox。
- CLI Channel、REST Web Service 和基础定时任务。
- SQLite 中的 Session、Tool Invocation、LLM Call 等持久化和审计写入。

### 扩展阶段

多租户、SSO、RBAC、完整 Tool Policy、容器或 microVM Sandbox、向量记忆、流式响应、多渠道 IM、Web 管理台、完整审计查询、集群高可用和跨节点 Agent 协作属于扩展或社区共建方向，除非需求明确纳入本次范围，否则不要提前实现。

AegisOS 负责 Agent 运行时，不负责可视化 Workflow 编排。Dify、Coze 等编排平台可以作为上层客户端调用 AegisOS。

## 运行链路

所有触发入口都应汇入同一个 AgentService：

```text
CLI / REST API / Scheduler
          ↓
     AgentService
          ↓
      ReActLoop
          ↓
 PromptBuilder 组装上下文
          ↓
 Provider 调用 LLM
          ↓
 ToolExecutor 执行工具
          ↓
 Sandbox 权限校验
          ↓
 ToolResult 回填并继续推理
          ↓
 Session、Memory、审计数据持久化
```

一个 Agent 目录的运行配置唯一来自 `.aegisos/agents/<dir>/profile.yml`，`AGENT.md` 保留任务指令。本轮 boot 的 ProfileLoader 只在启动/重启加载安全 YAML 和目录技能绑定；core 保存不可变 Profile/Registry，provider 通过同一个 DefaultProviderService 暴露直接/配置调用入口。禁止解析 AGENT.md frontmatter、扫描 `.aegisos/profiles/` 或每请求读盘回退。AGENT/Bootstrap/已绑定 SKILL 正文逐次安全重读；脚本/通知/调度不执行。

## 必须遵守的技术原则

- ReAct loop 由 AegisOS 自己实现，不直接使用 Spring AI 的 Agent 抽象。
- Spring AI 只负责 Provider、协议转换和 Tool schema；禁止启用 Spring AI 自动 Tool 执行，避免工具被执行两次。
- Provider 必须通过显式 provider name 映射到 `ChatModel`，不能依赖同类型 Bean 扫描推断供应商。
- 内置 Tool、MCP Tool 和 Java Plugin Tool 统一适配为 `AegisTool`，不要按来源拆成互不兼容的执行体系。
- Memory 对 ReAct 通过 `MemoryService` 统一门面访问，不把 Session 和长期记忆直接散落到循环代码中。
- Tool 执行必须经过 Sandbox；核心阶段的白名单校验不等于强隔离，不能把它描述成多租户安全边界。
- `tool_invocations` 和 `llm_calls` 的写入属于核心阶段基础能力，不能只写日志再留到以后补审计。
- API key、Token、密码、Webhook 凭证和完整连接串不得写入代码、文档、`AGENT.md` 或提交记录；使用环境变量或受控密钥存储。
- 不要把规划中的命令、模块、接口或性能指标写成“已实现”，除非仓库中已有实现并通过验证。

## 计划中的开发顺序

按依赖关系推进：

```text
US-1 Provider
    ↓
US-2 ReAct
    ↓
US-3 Memory  ─┐
              ├──→ US-5 Web Service
US-4 Tool    ─┘
```

每个 User Story 完成后，应执行针对该阶段的测试和一致性检查，再进入下一阶段。主体开发阶段计划使用 Spec-Kit 产出 `constitution`、`spec`、`plan` 和 `tasks`；小型增量适合使用手动提示词配合已有代码处理。

## 事实源读取方式

### 当前可读取的项目事实源

| 类别 | 读取入口 | 当前状态 |
|---|---|---|
| 产品定位 | `docs/IndustryResearch.md` | 已存在，设计资料 |
| 功能需求 | `docs/DemandAnalysis.md` | 已存在，设计资料 |
| 技术架构 | `docs/TechnicalSolution.md` | 已存在，设计资料，含版本演进内容 |
| AI 实施流程 | `docs/AiProgrammingGuide.md` | 已存在，设计资料，与技术方案按 14 个模块和 3 个 Demo 对齐 |
| 工程、API 基础、配置、监控 | 根/子模块 POM、web baseline、boot 配置和测试、`docs/engineering/` | 已有基础实现及 US-1 SQLite 审计；无业务 API 或生产部署验证 |

当源码和配置建立后，优先从以下仓内事实源取值：`pom.xml`、各模块 `pom.xml`、`src/main`、`src/test`、数据库迁移脚本、`application*.properties/yaml`、CI 配置、容器编排文件和部署文档。运行时配置中心、数据库、消息系统和监控平台的真值应以对应环境的受控读取结果为准，凭据只记录获取路径，不记录密钥内容。

## 修改和验证工作流

1. 先确认本次改动属于核心阶段、扩展阶段还是文档治理。
2. 阅读相关设计文档和仓内实现，找出事实、假设和未决项。
3. 对需求、技术方案和现有代码之间的冲突先提出决策，不自行扩大范围。
4. 采用最小改动，保留与本次任务无关的工作区修改。
5. 代码改动完成后运行仓库中实际存在且已核对过的构建、测试和格式检查命令。
6. 报告中分别说明代码验证、测试结果、文档结论和仍未验证的生产或外部依赖。

## 当前工程验证入口

```bash
mvn test
mvn clean package
bash scripts/check.sh quality        # 格式、测试、打包、SpotBugs
bash scripts/install-gitleaks.sh     # 本地校验安装固定版本
bash scripts/check.sh secrets        # 历史与工作目录
bash scripts/check.sh dependencies   # 依赖漏洞；数据源失败也阻断
```

首次格式修复用 `mvn spotless:apply`，避免无关业务代码重排。根目录及各模块的 `target/` 由 `.gitignore` 统一忽略；构建产物仅保留在本地，不加入版本控制。敏感配置仅通过环境或 CI Secrets 注入。

## 设计资料基线（尚未完整实现）

- 当前工程结构：宪法与根 POM 固定九个 Maven 模块；设计文档中的十四模块为后续设计口径，尚未落地。
- 场景验收：三个每日自动运行的端到端 Demo（每日天气、每日科技日报、每日 GitHub 日报）；Web Service 单独作为功能验收，不编号为 Demo。
- Agent 配置：005 已批准独立 profile.yml 与启动/重启快照；技能只从相对目录绑定派生，notify_channels 是名称引用。WorkspaceWatcher/热更新属于未来设计，必须另立规格，不能覆盖当前快照。
- Memory：`LongTermMemoryStore` 核心阶段支持三档后端，`MarkdownMemoryStore`（`.aegisos/memory/MEMORY.md`）为默认，同时提供 SQLite 和 Mem0；上层统一使用 `MemoryService`。
- 内置 Tool：核心阶段固定 9 个，清单以 `docs/TechnicalSolution.md` 第 6.2 节为准。

## 注意
配置文件使用 yaml 格式 ，代码要有详细中文注释。

测试模型使用 deepseek
模型凭据仅通过 `DEEPSEEK_API_KEY` 环境变量或受控密钥存储提供，不在指南中填写。

## Provider 课程补齐（005）事实边界

- 原 002 的 51 项任务、validation.md 与 V001 保留为历史。新增证据仅写 `specs/005-provider-course-alignment/validation.md`。
- `chat(sessionId, request)` 与 `chatProfile` 必须显式提供合法追踪值；不查会话表。旧入口 session_id=null。每次逻辑请求新 requestId，重试保持关联、attemptNo 递增。
- V2 新增 session_id/success/error_message，保留 status/error_code。STARTED 的 success/error_message 均空，成功 true/空，失败及拒绝 false/固定安全说明。begin 提交前禁止外呼，complete 失败 UNKNOWN 且停止重试。
- 005 历史阶段新库为 V2；US2 当前新库创建 V3，已有 V1/V2 均 MIGRATION_REQUIRED 拒绝，必须停机备份后离线手工迁移。不得在实现或测试中迁移个人库。
- `real-provider-smoke` 只在 boot 显式启用，真实模型为 DeepSeek。默认构建/CI不外呼；缺条件、零发现、跳过或旧报告一律不能 PASS。D-02 已于2026-10-22通过一次DeepSeek真实请求/非空响应/成功审计及重开核验；最终任务与门禁以005 validation为准，不自动进入ReAct。

## US2 当前实施边界

- 公共 AgentService 创建持久绑定 Profile 的 UUID 会话；process 显式传 session/profile/message，不静默新建/换 Agent。CLI：init / 单次 stdin chat / --session 续聊，stdout 只输出安全 JSON，诊断 stderr；无子命令保留原 Web 基础入口。
- V3 存储保存会话/处理/轮次/完整交换/工具审计及 Provider 请求关联；只有安全响应提交后才增加迭代数。网络外呼不处于数据库长事务中，审计先可靠提交。
- 默认最多10轮、最近20个 COMPLETED 用户处理；末轮工具只保存 OPEN，不执行，不发起下一轮。恢复遗留 RUNNING→INTERRUPTED，不重放旧模型/工具。
- 当前仅 http_get，targets 默认空；所有工具走 Sandbox 和持久审计。长期记忆默认空实现，不读个人 MEMORY.md，不提前声称完整 US3/US4 已交付。
- 003的SDS唯一平台限制已由007替代：Linux继续SDS，macOS使用Darwin目录FD/openat，Windows使用本地NTFS句柄。不得添加普通路径读取或关闭校验的回退；Windows无真实执行证据时仍记NOT_RUN，见007 validation。
- Provider可选thinking-mode仅接受显式disabled，启动冻结，每轮由生产mapper生成thinking.type=disabled；缺省无扩展，不按名称猜测。暂不支持thinking工具续轮，不保存reasoning_content。课程专用YAML必须disabled并参与指纹；旧真实FAIL保留，新授权新版真实天气PASS（3模型发送/1GET、零重试、同会话两处理完成）；003原课程阶段为141/141项；随后三项评审修复追加T142～T150，当前完成数与门禁见003 validation末尾。后续真实运行须明确启用。
- real-react-smoke 默认关闭；必须最终工程门禁通过且获得本次明确授权，最多两处理/6模型发送/2GET、重试0；历史 real-provider-smoke 不能替代天气事实验收或构成本次授权。
- 执行证据只更新003，不改写002/005历史；不自动提交/推送或迁移个人库。详见 docs/engineering/us2-runtime.md 与 us2-migration.md。

## 第18节 CLI 补齐（006）当前边界

- 006 在 US2 归档上新增12个叶命令与 Picocli 入口，TTY默认REPL、管道默认原七字段JSON，可显式指定模式。`--agent/--profile`互斥；交互默认`(cli,local,profile)`，不是认证身份。所有对话仍经 AgentService/MemoryService，不复制ReAct。
- 006 新空库版本为V4，新增session_bindings；V1/V2/V3非空库一律MIGRATION_REQUIRED，停机备份后显式离线迁移。保留V3历史/审计分表，不新增messages_json，不自动认领旧UUID。不迁移个人库。
- 轻命令不解析凭据、不建模型客户端或数据库。session list仅查询既有规范V4库，要求既有安全sidecar与独占短锁；WAL/SHM/journal无法证明零写时拒绝，不初始化/恢复/读写回退。
- Profile管理只创建安全模板或原子移出已验证来源到回收目录，不覆盖、不递归跟随链接、不级联删除历史。配置继续YAML和启动快照，正文安全目标不变；007将SDS唯一限制替换为三平台受控句柄。
- serve只提供本机Web基础和健康，不含Agent业务API；gateway明确未启用、退出6。第18节完整三模式仍延期；T076在独立新授权下完成真实CLI天气PASS（3次模型发送/1GET、零重试、同会话两处理、quit及关闭重开核验），不复用US2真实结果。真实JAR进程天气未另跑，进程层沿用本节回环探针；后续真实外呼需对应授权。
- 本轮任务与执行证据仅写006，不改002/003/005历史；默认只跑临时库/回环替身。使用见 docs/engineering/cli-usage.md；代码阅读/复审/验证见 docs/engineering/006-cli-course-alignment-changes-review-verification.md。代码提交/推送仍按用户单独指令执行。

## 三平台运行（007）

- 当前跨平台改动叠加在未提交006之上，证据只追加specs/007-cross-platform-runtime，不覆盖002/003/005/006历史。Java21、九模块、V4和业务范围不变。
- core/platform封装SDS、Darwin和Windows/JNA5.17.0；领域、CLI、storage仅调用门面。身份、ABI、原生库或权限不能验证时拒绝，禁止弱化回退。
- Unix新建目录700/文件600；Windows初始受保护owner DACL。数据库/sidecar单硬链接、独立锁生命周期、JVM先认领、只读零初始化/恢复仍必须验证。Windows仅本地NTFS盘符；URI/ADS/UNC/映射网络卷拒绝。
- .github/workflows/engineering.yml新增三OS矩阵。缺少Windows CI/实机执行不能宣称三平台全部通过；同一工作区Maven串行，默认仅回环模型和临时库。
- IDEA入口、环境变量与YAML使用见docs/engineering/cross-platform-runtime.md；本轮重点review和复跑方法见docs/engineering/007-cross-platform-runtime-changes-review-verification.md。不自动commit/push、真实模型调用或个人库迁移。

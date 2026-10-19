# AegisOS Agent Guide

## 项目定位

AegisOS 是一个基于 Java 的企业级 Agent OS 运行时底座，目标是部署在企业自己的服务器、虚拟机或 Kubernetes 环境中，为多个业务 Agent 统一提供模型调用、ReAct 推理、记忆、工具、渠道、调度、审计和 Web 接入能力。

当前仓库处于设计准备阶段。仓库内目前只有 `docs/` 设计文档，没有 Java 源码、Maven 工程、测试工程或已验证的构建命令。文档中的命令、模块和接口在实现前都必须重新核对，不能当作已经存在的事实。

## 阅读顺序与事实源

开始任何实现、评审或测试前，先阅读以下文件：

1. `docs/IndustryResearch.md`：项目定位、行业背景、竞品边界和长期方向。
2. `docs/DemandAnalysis.md`：功能需求、非功能需求、核心与扩展范围、验收目标。
3. `docs/TechnicalSolution.md`：架构、模块、数据持久化、接口和技术决策。
4. `docs/AiProgrammingGuide.md`：Spec-Kit、User Story 和实施顺序。

文档版本已统一到下方的项目基线。后续修改应同步更新四份设计文档，避免重新引入旧口径。

## 项目边界

### 核心阶段

核心阶段交付 Agent OS 的运行时内核，主要包括：

- JDK 21、Spring Boot 3.x 单体应用和 Maven 多模块工程。
- Provider 抽象，基于 Spring AI Alibaba 接入主流 LLM。
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

一个 Agent 由 `.aegisos/agents/<name>/AGENT.md` 定义：frontmatter 派生运行时 Profile，正文作为任务指令进入 system prompt；可选的 `skills/` 软连接、`scripts/` 和参考文件按需读取。Agent 目录是上下文来源，不是 Tool。

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
| API、配置、数据库、消息缓存、环境域名、监控 | 待源码、配置和部署文件建立后读取 | 当前仓库暂无可验证事实源 |

当源码和配置建立后，优先从以下仓内事实源取值：`pom.xml`、各模块 `pom.xml`、`src/main`、`src/test`、数据库迁移脚本、`application*.yaml`、CI 配置、容器编排文件和部署文档。运行时配置中心、数据库、消息系统和监控平台的真值应以对应环境的受控读取结果为准，凭据只记录获取路径，不记录密钥内容。

## 修改和验证工作流

1. 先确认本次改动属于核心阶段、扩展阶段还是文档治理。
2. 阅读相关设计文档和仓内实现，找出事实、假设和未决项。
3. 对需求、技术方案和现有代码之间的冲突先提出决策，不自行扩大范围。
4. 采用最小改动，保留与本次任务无关的工作区修改。
5. 代码改动完成后运行仓库中实际存在且已核对过的构建、测试和格式检查命令。
6. 报告中分别说明代码验证、测试结果、文档结论和仍未验证的生产或外部依赖。

## 已冻结的项目文档基线

- 工程结构：技术方案第 10 章定义 14 个 Maven 模块，AI 编程指南按同一数量拆解。
- 场景验收：三个每日自动运行的端到端 Demo（每日天气、每日科技日报、每日 GitHub 日报）；Web Service 单独作为功能验收，不编号为 Demo。
- Agent 配置：`WorkspaceWatcher`、`AgentLoader` 和 `ContextLoader` 运行时监听并每轮重新读取，配置和绑定在下一轮生效，无需重启。
- Memory：`LongTermMemoryStore` 核心阶段支持三档后端，`MarkdownMemoryStore`（`.aegisos/memory/MEMORY.md`）为默认，同时提供 SQLite 和 Mem0；上层统一使用 `MemoryService`。
- 内置 Tool：核心阶段固定 9 个，清单以 `docs/TechnicalSolution.md` 第 6.2 节为准。

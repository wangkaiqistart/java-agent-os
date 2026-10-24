# AegisOS

<p align="center">
  <img src="docs/images/logo.svg" alt="AegisOS logo" width="560">
</p>

> Java 原生的企业级 Agent OS 运行时底座：私有部署、统一接入、可审计、可扩展。

[![工程初始化](https://img.shields.io/badge/status-engineering%20baseline-7c3aed)](docs/engineering/initialization.md)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](docs/TechnicalSolution.md)
[![Spring Boot 4.x](https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F?logo=springboot&logoColor=white)](docs/TechnicalSolution.md)
[![License: TBD](https://img.shields.io/badge/license-TBD-lightgrey)](#许可证)

AegisOS 运行在企业自己的服务器、虚拟机或 Kubernetes 环境中，为多个业务 Agent 统一提供大模型调用、ReAct 推理、记忆、工具、渠道、定时任务、审计和 REST 接入能力。业务方通过 Agent 目录描述“做什么”和“怎么运行”，不需要为每个 Agent 重复编写后端服务。

> 当前使用 Java 21 / Spring Boot 4.1.1 / Spring AI 2.0.1 / Maven 九模块。已实现显式 Provider、有限重试、ReAct、持久会话、唯一 Sandbox 下的 http_get，以及 init / 单次 stdin chat CLI；长期记忆默认空，业务 Web API、其他工具、通知和调度尚未实现。US2 离线测试、工程门禁和显式 `thinking-mode: disabled` 下的真实天气验收已通过（3次模型发送、1次天气GET、同会话两次处理完成）；首次失败记录保留，暂不支持thinking工具续轮，详见 [US2 验证记录](specs/003-react-loop/validation.md)。生产指令正文读取要求 SecureDirectoryStream，Linux 完成正向验收，macOS 明确拒绝读取。原 [US1](specs/002-provider-abstraction/validation.md) / [课程补齐](specs/005-provider-course-alignment/validation.md) 历史保留。

> 后续代码评审的三项修复及回归、Maven 和复审记录追加在 [US2 验证记录末尾](specs/003-react-loop/validation.md#代码评审修复增量t142t1502026-10-22)，原任务与真实验收历史保留。

## 为什么做 AegisOS

企业真正需要的是一个私有、可控、可审计的 Agent 统一底座：数据留在自己的基础设施，模型和工具可以替换，Agent 能接入现有 ERP、CRM、CMDB、知识库和监控系统。AegisOS 补齐 Java/Spring 生态在 Agent OS 这一层的缺口，复用企业已经熟悉的 Spring Boot、JVM 和运维体系。

AegisOS 专注运行时，不承担可视化 Workflow 编排。Dify、Coze 等平台可以作为上层客户端，通过 REST API 调用 AegisOS。

## 核心能力与状态

| 能力 | 说明 |
| --- | --- |
| Provider（已实现） | OpenAI 兼容协议、显式 Provider/模型、独立 YAML Profile 与启动快照、可配置有限重试、会话关联及 SQLite 逐次审计（当前新库 V3）；schema 只翻译不执行。DeepSeek deepseek-flash 已通过一次真实单请求与持久审计验证。 |
| ReAct Loop（已实现） | 自实现有界循环、显式处理/轮次关联、末轮工具停止、完整交换裁剪、失败与重启中断。 |
| Memory（会话已实现） | 统一 MemoryService 门面和 V3 SQLite 持久会话；长期记忆当前为空，Markdown/SQLite/Mem0 长期后端属于后续范围。 |
| Tool（最小能力已实现） | 当前仅 http_get、精确网络白名单、资源限制和持久审计；其余 8 工具、MCP 与 Java Plugin 扩展待实现。 |
| CLI（已实现） | init 离线初始化；chat 单次 stdin、JSON 结果、显式 session 续聊，专用非 Web 运行时。 |
| Web Service（基础设施） | 当前只有基础 HTTP/健康/监控；业务 REST API 待实现。 |

## 架构

以下为目标架构；当前 Provider 可独立调用，不依赖其余业务组件。

![AegisOS architecture](docs/images/architecture.svg)

所有入口都汇入同一个 `AgentService`，因此 CLI、REST API 和定时任务共享相同的 ReAct、Tool 执行、Memory 和审计链路。

```text
CLI / REST API / Scheduler
            ↓
       AgentService
            ↓
        ReActLoop
            ↓
 PromptBuilder → Provider → LLM
            ↓
       ToolExecutor
            ↓
  Sandbox → Tool / MCP / Java Tool
            ↓
 Session / Memory / Audit Storage
```

关键技术边界：

- ReAct loop 由 AegisOS 自己实现，不使用 Spring AI 的 Agent 抽象。
- Spring AI 只负责 Provider、协议转换和 Tool schema 生成，禁止启用自动 Tool 执行。
- Provider name 到 `ChatModel` 使用显式映射，避免多 Provider 场景下的类型歧义。
- 内置 Tool、MCP Tool 和 Java Plugin Tool 统一适配为 `AegisTool`。
- Tool 执行先经过 Sandbox；核心阶段的白名单校验不等于容器级或 microVM 级隔离。
- `tool_invocations` 和 `llm_calls` 在核心阶段就写入持久化层，不能只依赖日志。

## Agent 定义

一个 Agent 对应一个目录：

```text
.aegisos/
├── agents/
│   └── <name>/
│       ├── profile.yml    # 唯一运行配置，启动/重启加载
│       ├── AGENT.md       # 每次构建 Prompt 安全重读正文
│       ├── skills/        # 公共 Skill 的相对软连接
│       ├── scripts/       # 可选脚本
│       └── REFERENCE.md   # 可选参考资料
├── skills/                # 公共 Skill 实体库
├── memory/
│   └── MEMORY.md          # 长期记忆
├── aegisos.db             # V3 会话与持久审计
├── logs/                  # 结构化日志
├── AGENTS.md              # 项目级 Bootstrap
├── SOUL.md                # Agent 人格 Bootstrap
└── USER.md                # 用户偏好 Bootstrap
```

`profile.yml` 是运行配置的唯一来源，`AGENT.md` 保留任务指令。Profile、全局 Provider 和技能目录绑定均在启动/重启时生成不可变快照；AGENT/Bootstrap/SKILL 正文每次构建重读并组装 Prompt，不执行脚本、通知或调度。技能只从 `skills/` 相对软连接派生，YAML 不允许 `skills:`；`notify_channels` 仅保存通知名称引用。

```yaml
name: course-smoke
provider:
  name: deepseek-main  # 必须存在于全局 Provider 配置
  model: deepseek-flash  # 必须在该 Provider 的允许列表中
```

Java 调用入口：`ProviderService.chat(sessionId, request)` 与 `ProfileProviderService.chatProfile(sessionId, profileName, prompt)` 共用一个审计/重试实现。会话标识仅校验格式、用于追踪，不证明会话存在或已认证；旧 `chat(request)` 明确未关联，审计 `session_id=null`。

## 计划中的开发顺序

```text
US-1 Provider
    ↓
US-2 ReAct
    ↓
US-3 Memory  ─┐
              ├──→ US-5 Web Service
US-4 Tool    ─┘
```

核心阶段先交付运行时内核；多租户、SSO、RBAC、完整 Tool Policy、容器或 microVM Sandbox、向量记忆、流式响应、多渠道 IM、Web 管理台、完整审计查询、集群高可用和跨节点 Agent 协作属于后续扩展或社区共建。

## 开发快速开始

需要 Java 21 和 Maven 3.9.x，在仓库根执行：

```bash
mvn test
mvn clean package
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar
```

已有 V1/V2 审计库启动会返回 `MIGRATION_REQUIRED`，需停机、一致性备份后按[V3 迁移指南](docs/engineering/us2-migration.md)手工升级（V1 先按005历史指南升级至有效V2）；测试使用临时库，不改个人库。

默认仅监听 `127.0.0.1:8080`，健康检查为 `GET /actuator/health`。开发文档、可选本机 Prometheus、API 错误约定和 CI 门禁见[开发指南](docs/engineering/development.md)。

```bash
bash scripts/check.sh quality
bash scripts/install-gitleaks.sh
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

## 规划中的产品命令

以下命令描述目标使用方式，当前尚未实现，不能作为现阶段的运行验证：

```bash
# 初始化工作区
aegisos init

# 创建一个 Agent
aegisos profile create hello-agent

# 配置环境变量后启动交互式对话
export DEEPSEEK_API_KEY=<your-api-key>
aegisos chat --profile hello-agent

# 启动 REST API
aegisos serve --port 8080
```

业务系统计划通过以下类型的接口接入：

```text
POST /api/v1/sessions
POST /api/v1/sessions/{id}/messages
GET  /api/v1/sessions/{id}
POST /api/v1/agents/{name}/invoke
GET  /api/v1/health
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [行业调研](docs/IndustryResearch.md) | Agent OS 定义、行业空白、Java 生态位置和长期愿景。 |
| [需求分析](docs/DemandAnalysis.md) | 功能需求、非功能需求、核心/扩展边界和验收目标。 |
| [技术方案](docs/TechnicalSolution.md) | 架构、模块、数据、接口、Sandbox、Memory 和实施节奏。 |
| [AI 编程指南](docs/AiProgrammingGuide.md) | Spec-Kit、User Story、实施依赖和增量开发方式。 |
| [开发指南](docs/engineering/development.md) | 启动、Profile、API 约定、质量与安全门禁。 |
| [初始化报告](docs/engineering/initialization.md) | 实际变更、测试证据和未完成验证。 |
| [Provider 课程补齐](specs/005-provider-course-alignment/quickstart.md) | Profile YAML、会话审计、离线迁移及默认关闭的 DeepSeek 冒烟；真实单次验收 PASS（2026-10-22）。 |
| [Provider 开发与验证](specs/002-provider-abstraction/quickstart.md) | US-1 配置、Java 调用边界、本地测试和审计验证。 |
| [US2 变更、Review 与验证](docs/engineering/us2-changes-review-verification.md) | 模块与调用链、重点审查清单、三项修复反例、可复跑命令及已执行结果。 |
| [Agent 指南](AGENT.md) | 面向 AI Agent 的仓库协作约定和事实源读取方式。 |

## 当前状态与开发前置事项

当前工程按宪法维护九个 Maven 模块，设计资料中的十四模块尚未落地。US-1 Provider 已提供 Java 接口和 SQLite 审计，开发/验证入口见 [Provider 指南](specs/002-provider-abstraction/quickstart.md)及[验证记录](specs/002-provider-abstraction/validation.md)。005 课程补齐已提供本地验证和显式 `real-provider-smoke` 入口；默认启动/test/package/quality/CI 不真实调用，D-02 已于 2026-10-22 通过真实单次请求、非空响应与重开审计验证；最终放行及当前门禁见本轮验证记录。具体进度以[本轮验证](specs/005-provider-course-alignment/validation.md)为准。US2 ReAct 的当前进度与独立天气验收见 [003 验证记录](specs/003-react-loop/validation.md)；005历史真实验证限定于 DeepSeek 指定配置，不表示其他厂商或生产部署验收。

主体开发计划使用 Spec-Kit 生成 `constitution`、`spec`、`plan` 和 `tasks`，小型增量功能使用手动提示词配合已有代码。

## 参与贡献

项目进入代码实现阶段后，建议按以下方式贡献：

1. 先阅读本 README 和 `AGENT.md`，再阅读相关需求与技术方案章节。
2. 对跨模块或核心能力改动，先补充或更新对应的设计与验收说明。
3. 保持变更范围最小，补充必要测试，并运行仓库中已验证的质量检查命令。
4. 在 Pull Request 中说明背景、行为变化、验证结果和未解决风险。

## 许可证

当前仓库尚未包含 `LICENSE` 文件。正式开源发布前需要由项目维护者确定并补充许可证。

## US2 CLI 快速体验

先构建，在空临时目录初始化；这一步不需要凭据、不建库、不联网。

```bash
mvn -B clean package
US2_WORKSPACE="$(mktemp -d)"
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar init --workspace "$US2_WORKSPACE"
```

在支持 SecureDirectoryStream 的 Linux 上，编辑工作区 `.aegisos/config.yml` / `agents/weather/profile.yml`，从受控环境注入 `DEEPSEEK_API_KEY` 后，可执行一次真实 chat（会消耗模型额度）：

```bash
printf '%s' '你好，请简短介绍自己。' | java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar chat --workspace "$US2_WORKSPACE" --agent weather
```

stdout 为七字段 JSON；续聊增加 `--session <返回的sessionId>`。默认工具 targets 为空，天气目标需人工批准。详细配置、退出码、默认关闭的受预算天气验收见 [US2 指南](specs/003-react-loop/quickstart.md)；旧库须按 [V3 手工迁移](docs/engineering/us2-migration.md) 停机备份后操作，运行时不会自动升级。

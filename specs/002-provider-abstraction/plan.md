# 实施计划：US-1 LLM Provider 抽象

**功能标识**：`002-provider-abstraction` | **日期**：2026-10-20 | **规格**：[spec.md](spec.md)

**输入**：已完成五项澄清的 US-1 规格与宪法 1.0.1。

**工作区事实**：活动功能由 `.specify/feature.json` 解析；当前 Git 分支为 `main`，尚未创建或切换同名功能分支。本次仅产出设计，不修改 POM/Java、不生成 `tasks.md`。

## 概要

提供统一的 `ProviderService`，按 Provider 名称和模型名称显式路由。Spring AI 仅提供协议客户端，AegisOS 负责配置快照、错误分类、有限重试和逐次持久审计。每次外呼前必须提交审计记录；客户端关闭内部重试与自动 Tool 执行。US-1 不实现 ReAct、Memory、Tool 执行或 HTTP 业务端点。

## 技术上下文

| 项目 | 当前事实与实施决策 |
|---|---|
| 语言与工具 | Java 21；本轮检查为 Zulu 21.0.9、Maven 3.9.14；无 Maven Wrapper |
| 工程 | 九模块 Maven reactor；Java 包前缀 `com.aegisos`，Maven groupId 为 `io.aegisos` |
| 框架 | 现有 Boot 3.3.5；计划升至 Boot 3.5.10，锁 Spring AI 1.1.2；Alibaba DashScope 扩展适配时使用 1.1.2.4-security-fix。已研究发布元数据/API，项目组合构建待实施验证 |
| 模型客户端 | 首批实现 OpenAI 兼容适配器，支持 DeepSeek/Kimi 配置；本次不强制实现第二套协议 |
| 存储 | 沿用 SQLite + Spring Data JPA；`llm_calls` 写入前置到 US-1。Hibernate core/community dialect 均锁 Boot 管理的 6.6.41.Final；SQLite JDBC 锁已发布的 3.53.4.0，组合兼容性由实施测试验证 |
| 测试 | JUnit Jupiter、Spring Boot Test、Mockito、本地 HTTP stub、临时 SQLite 文件；显式配置 Surefire 3.5.2 运行 `*Test`，防止旧默认插件漏跑 Jupiter |
| 平台 | Java 21 单进程；开发支持 macOS/Linux，部署目标为 Linux |
| 性能边界 | 规格无 QPS/延迟 SLA，不新增无依据指标；连接/读取均有正数超时，外呼不持数据库事务；真实请求次数最多 `maxRetries + 1` |
| 规模与范围 | 至少两个命名 Provider；单次非流式文本/结构化 Tool 响应；无 fallback、竞速、批量、流式或成本看板 |

## 宪法检查

研究前按九模块与 core 契约方案检查通过。设计后复核如下，均为设计通过，不代表实施测试通过。

| 原则 | 设计依据与门禁 |
|---|---|
| 模块边界 | core 定义 DTO/Provider/审计端口；provider/storage 分别实现，boot 装配；provider 不依赖 storage/memory/tool/web |
| 显式 Provider | 配置列表校验重名后创建不可变映射；模型必填且属于允许集合；不扫描 `ChatModel` 推断身份 |
| ReAct 归属 | 仅返回一次模型响应；关闭自动工具执行，结构化调用交还调用方 |
| Memory/Sandbox | 本次不访问 Memory、不执行 Tool；后续 Tool 执行仍必须经过 Sandbox |
| 测试与构建 | 每个故事有测试矩阵；聚焦测试、完整 `mvn test` 与 `mvn clean package` 成功后才允许 US-2 依赖 |
| 安全审计 | 凭证来自环境变量引用；日志/审计不保存正文、密钥或完整连接数据；逐次提交，写失败不外呼 |
| 配置与中文 | Provider 配置仅启动/重启生效，与 Agent 指令每轮读取区分；功能产物用中文 |

**一致性处理**：宪法和 POM 是九模块，`docs/` 的十四模块演进不纳入 US-1；`AGENT.md` 中“暂无 Java/Maven 工程”的状态已过时，以源码/POM 为准。本次不批量改写其他文档。SQLite/JPA 保持既有选型。审计存储故障时无法写拒绝记录，已在 spec FR-009、SC-003、SC-009 限定可写条件及尝试边界，并注明外呼后终态写入失败是计划补充设计。

## 项目结构

### 功能文档

```text
specs/002-provider-abstraction/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── provider-api.md
│   └── provider-config.md
└── checklists/requirements.md
```

`tasks.md` 留给 `$speckit-tasks US1`。

### 源码落点（新增内容为计划）

```text
aegisos-core/src/main/java/com/aegisos/core/
├── provider/       # ProviderService、请求/响应、消息、schema、错误
└── audit/          # LlmCallAuditStore、审计状态与安全元数据
aegisos-provider/src/main/java/com/aegisos/provider/
├── config/         # 配置快照、显式注册表
├── adapter/        # OpenAI 兼容适配器与协议转换
└── service/        # 审计门槛、错误分类、有限重试
aegisos-storage/src/main/java/com/aegisos/storage/audit/
                   # JPA Entity/Repository、审计端口实现
aegisos-storage/src/main/resources/db/schema/sqlite/
                   # 版本化审计表初始化资源
aegisos-boot/src/main/java/com/aegisos/boot/config/
                   # 配置绑定、凭证解析及显式装配
各相关模块/src/test/java/com/aegisos/...
                   # 单元、HTTP stub、SQLite、装配与安全测试
```

依赖为 `provider → core ← storage`，boot 注入接口。现有 storage POM 含 memory 依赖，但审计路径不调用 Memory；最小测试上下文不注册 Memory 业务 Bean。boot 已有其他模块依赖不等于 US-1 依赖其业务能力。

## Phase 0：研究

[research.md](research.md) 收敛版本兼容、客户端构造、关闭隐式重试/工具、JPA 事务、故障语义及文档差异。技术选择已确定；依赖组合编译、方言/驱动兼容和真实传输计数是实施验证事项。

## Phase 1：设计

1. core 定义纯 Java DTO/端口，不泄漏框架或厂商类型。
2. 启动时校验配置，重复名称拒绝启动；显式构造客户端并冻结注册表。缺凭证等配置使对应 Provider 不可用，无启动探测外呼。
3. 请求缺名称/模型等问题在外呼前拒绝；审计可写时落 `REJECTED`，否则返回审计不可用。
4. 每次尝试独立提交 `STARTED`，外呼后另一事务更新终态；仅临时网络/超时允许外层重试，默认零次。
5. 透传消息/schema，返回结构化 Tool 调用，客户端单次发送且不执行工具。
6. 终态提交失败保留 STARTED，返回结果未确认，停止自动重试。

字段见 [data-model.md](data-model.md)，公开接口见 [provider-api.md](contracts/provider-api.md)，配置见 [provider-config.md](contracts/provider-config.md)。本次没有 REST 端点，不生成 OpenAPI。

## 实施分段与验收映射

| 分段 | 后续产出 | 对应验收 |
|---|---|---|
| 基础 | 兼容依赖、Surefire、core 契约 | FR-001/013/015；实际测试发现数大于零、九模块编译 |
| 审计基础 | SQLite/JPA 审计端口 | FR-008/009/010/016；SC-003/004/009；独立连接可见 STARTED、重开数据库记录保留 |
| 故事 1 | 单 Provider、用量、错误、有限重试 | FR-004/006/006a/007；SC-006；外部请求计数与重试类别 |
| 故事 2 | 注册表、模型集合、启动快照 | FR-002/003/005/006b/014；SC-001/002/007/008；A/B 路由及重启对照 |
| 故事 3 | 拒绝审计、终态故障、安全断言 | FR-008～012/016；SC-003/004/009；无工具执行和秘密泄漏 |
| 收口 | boot 最小装配、完整测试/打包 | SC-005；无其他业务服务依赖，完整 reactor 成功 |

必须覆盖：缺名称/模型、未知组合、重复配置、缺凭证、超时/临时网络、400/401/429/5xx、缺用量、Tool 返回、重试耗尽、首次/后续前置审计失败、终态提交失败、重启 STARTED 不重放、数据库锁/磁盘异常、敏感哨兵。模型 stub 收到请求时以另一连接读取 STARTED，证明已提交；模型阻塞时另一个写事务可提交，证明无跨网络长事务。

## 验证与交付边界

步骤见 [quickstart.md](quickstart.md)。本轮仅验证文档完整性、内部链接、规格映射与空白格式；不将骨架构建等同于 Provider 完成。实施交付须给出 Surefire 测试数量、真实 HTTP stub 计数、SQLite 证据及完整 Maven 结果。真实厂商调用单独标注，不作为无凭证 CI 门槛。

## 复杂度记录

无宪法豁免。四个现有模块内的契约、适配器和一张审计表即可覆盖；不新增模块、消息队列、分布式事务或通用工作流框架。

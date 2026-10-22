# 实现计划：Provider 课程对齐与补齐验收

**功能标识**：`005-provider-course-alignment` | **日期**：2026-10-22 | **规格**：[spec.md](spec.md)

**分支**：当前实际 Git 分支为 `main`；setup-plan 返回的 BRANCH 是活动功能标识，本轮未创建或切换分支。

**输入**：已澄清补齐规格、PDF16-01～PDF16-07、原 US-1 源码及验证历史。本文及配套文档为拟实施设计，不表示代码或课程验收已经完成。

## 概要

在现有 Provider、有限重试及 SQLite 前置审计上增量补齐五个故事：A 配置加载与路由、B 会话关联与审计兼容、C schema 完整传递、D 独立真实冒烟、E 回归与可追溯交付。保留原 US-1 调用入口、五字段 ModelRequest、51 项任务及验证历史。

Profile 只从 Agent 目录内 `profile.yml` 启动加载，skills 从相对软连接派生，建立不可变索引。新增带 sessionId 的直接调用及按 Profile 调用入口，全部汇入现有唯一审计/重试边界。新增物理审计列并通过手工离线迁移保留旧数据。schema 在实际 HTTP JSON 上验证；真实 DeepSeek 冒烟使用独立 Maven profile，默认不发现、不启动、不外呼。

## 技术上下文

| 项目 | 设计与当前依据 |
| --- | --- |
| 语言 / 构建 | Java 21；Maven 多模块；现有根 POM 九模块不变 |
| 主要依赖 | Spring Boot 4.1.1、Spring AI 2.0.1；保留安全覆盖；boot 显式声明 BOM 管理的 SnakeYAML 2.6，不引入其他厂商 starter 或迁移框架 |
| 领域边界 | core 仅 JDK 类型；provider 依赖 core 审计端口；storage 实现 JPA/SQLite；boot 负责文件、YAML、环境及装配 |
| 存储 | SQLite llm_calls，默认 .aegisos/aegisos.db；新增 V2 schema 与 V1→V2 手工迁移；Hibernate ddl-auto: none |
| 测试 | 现有 JUnit Jupiter、Surefire 3.5.2、本地 HTTP stub、临时目录与真实临时 SQLite；拟新增仅 boot 生效的 Failsafe 3.5.2 |
| 运行平台 | Java 21 单机进程，macOS/Linux 文件系统，相对软连接；无容器隔离或多租户承诺 |
| 项目形态 | Java 服务接口及 Boot 装配，本轮无业务 HTTP/CLI 端点 |
| 性能与容量 | 不新设吞吐/SLA；Profile 启动扫描一次；单文件最多 256 KiB、最多 32 层；请求不读盘，网络不持有数据库事务 |
| 安全约束 | YAML 安全构造及资源限制；不展开正文环境变量；凭据不进入 Profile/日志/审计/报告；SDK/传输不重发，不自动执行 Tool |
| 外部验收条件 | DeepSeek 模型、受控凭据及网络；真实调用仍为 NOT_RUN。当前 /v1 兼容性未经真实请求验证，失败不得自动换路径/模型 |
| 范围 | 23 项 FR、10 项 SC、20 个场景；非 Provider 元数据只解析，不交付 ReAct/Memory/技能执行/通知/调度/会话存储 |

技术选型与接口决定见 [research.md](research.md)，字段及状态见 [data-model.md](data-model.md)。厂商可用性是实施后验收条件，不是未决定的项目设计。

## 宪法检查

研究前按批准规格检查，设计后再核对接口/依赖，两次均为**设计符合**。实现测试和安全门禁尚未执行，不能记为通过。

| 宪法门槛 | 研究前依据 | 设计后核对 |
| --- | --- | --- |
| 九模块与稳定契约 | 不新增模块 | Profile/会话/审计契约在 core；文件在 boot；provider 不引用 storage 实现，无反向依赖 |
| 显式 Provider / 自有 ReAct | 明确名称和模型 | Profile 不可被请求覆盖；单次 ChatModel.call；禁止 Advisor/工具执行，不实现 ReAct |
| Memory 门面 / Sandbox | 本轮不执行 Memory/Tool | 仅传工具说明和意图；tools 名单不声称提供 Sandbox 隔离 |
| 每故事测试与构建 | A～E 均可独立验收 | [验收契约](contracts/acceptance-harness.md)与 [quickstart](quickstart.md)列出聚焦测试、reactor test/package 及依赖故事阻断 |
| 安全与持久审计 | 保留前置门槛/未知结果 | Profile 拒绝同样审计；兼容列原子更新；迁移不伪造会话或重放 |
| 配置语义与中文 | 启动快照、YAML、中文 | 不监听、不逐轮重载；后续关键代码/测试有详细中文注释 |
| 增量和真实证据 | 保留原任务/历史/测试 | 新证据写本功能 validation；原 91 项/NVD 结果只作历史 |

无需要豁免的宪法冲突，不引入额外复杂性审批项。

## 项目结构

### 本功能文档

```text
specs/005-provider-course-alignment/
├── spec.md                         # 已澄清，保持原文
├── checklists/requirements.md       # 已有清单，保持原文
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── contracts/
    ├── profile-config.md
    ├── provider-api.md
    ├── audit-storage.md
    └── acceptance-harness.md
```

tasks.md 由下一步 `$speckit-tasks` 生成；validation.md 与脱敏证据由实施/验收阶段新增。本轮不生成任务或虚构验证记录。

### 拟修改的实现位置

```text
aegisos-core/src/{main,test}/java/com/aegisos/core/
  profile/                         # Profile、只读索引、诊断元数据
  provider/                        # 新入口、ProfilePrompt、安全错误
  audit/                           # sessionId 兼容参数、安全结果说明
aegisos-provider/src/{main,test}/java/com/aegisos/provider/
  service/                         # 共用入口、延迟 Profile 查找、审计/重试
  adapter/                         # schema 校验、线上 JSON、传输发送计数
aegisos-storage/src/{main,test}/java/com/aegisos/storage/audit/
                                   # V2/JPA/原子更新/迁移验证
aegisos-storage/src/main/resources/db/
  schema/sqlite/V002__llm_calls.sql
  migration/sqlite/V002__provider_course_audit.sql
aegisos-boot/src/{main,test}/java/com/aegisos/boot/config/
                                   # ProfileLoader、安全路径、启动装配
aegisos-boot/src/test/java/com/aegisos/boot/provider/ProviderSmokeIT.java
aegisos-boot/src/{main,test}/resources/
                                   # 现有 YAML 语义测试、冒烟 YAML 样例
aegisos-boot/pom.xml                # SnakeYAML、独立冒烟 profile
scripts/verify-provider-smoke.py    # 拟新增标准库报告核验器，不接触密钥/正文
```

其他五模块参与 reactor 回归，不新增业务。配置统一 YAML；POM、SQL、Spec Kit 元数据保留原生格式。

## 关键设计

1. **配置链**：全局 Provider 快照 → 安全扫描 Agent → YAML/软连接校验 → 按 name 分组排除全部重名项 → 不可变 ProfileRegistry。坏文件隔离；空目录安全提示；不从 AGENT.md 或第二目录回退。
2. **调用链**：先生成 requestId → 校验会话/Profile/请求 → 安全拒绝审计，或进入原循环 → 每次 begin 提交 → 单次模型调用 → complete 提交 → 仅明确可重试且有额度时再次尝试。不能在外层查 Profile 后未审计抛错。
3. **兼容**：旧 chat(ModelRequest) 未关联；新 chat(String, ModelRequest) 必须有 sessionId；ProfileProviderService.chatProfile(String, String, ProfilePrompt) 由同一实现 bean 提供。ModelRequest 原五字段及审计 DTO 旧构造方式保留；core 无 Spring AI Prompt。
4. **数据库**：新空库可初始化 V2；V1 库拒绝启动并提示离线迁移。V001 原文保留，升级手工执行，不自动 ALTER、修复 STARTED 或补发。
5. **工具**：公开 JSON Schema 子集；比较最终 HTTP JSON；不支持则审计拒绝。strict(false)、直接调用、不可执行描述符与零执行测试共同保证边界，不套用本版本不存在的开关。
6. **冒烟**：DeepSeek、显式模型/sessionId、生产配置装配、临时 SQLite、禁重试、无工具；独立 Failsafe。核对本次报告、物理发送计数、非空文本和重开审计；缺条件/零测试/跳过为 NOT_RUN，实际运行失败为 FAIL。

## 阶段与交付顺序

**阶段 0 研究设计已完成**：安全 YAML、不可变索引、兼容入口、V2 手工迁移、schema 子集与 Failsafe 发现规则均已决定，见 research。

**阶段 1 契约设计已完成**：本计划、数据模型、四份契约及 quickstart；本轮仅检查文档结构、引用和保护范围。

**后续顺序**：共享 core 契约 → B 审计/迁移与服务基础 → A 加载/装配/Profile 调用 → C schema → D 冒烟及本地负例 → E 全量回归、文档同步、真实验收。各故事聚焦测试及 test/package 通过后，才能开始依赖故事；E 证据贯穿各步。D 默认/负例测试通过不代表真实成功场景完成。

实施时同步 AGENT.md、README、需求/技术/实施指南的 profile.yml、启动快照与字段来源；标明本轮与未来能力。课程原文、旧 US-1 任务/验证历史不改。详细中文注释应解释用途、参数/返回/错误、配置来源、路径边界、审计提交顺序、迁移兼容、重试及冒烟限制，不以注释数量替代正确性。

## 验证与证据

[验收契约](contracts/acceptance-harness.md)给出 PDF→FR/SC→20 场景→拟测试类映射；[quickstart](quickstart.md)区分现有命令与实施后命令。拟测试类是设计名称，不能计为已交付。

本轮不运行 Maven 构建、迁移、安全扫描或真实模型请求。原 US-1 的 91 项测试及安全扫描只作历史；实施后重新 test、clean package、quality、secrets、dependencies。依赖扫描沿用受控环境/Keychain 注入流程，不复制或读取密钥到本功能文档。

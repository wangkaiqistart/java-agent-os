# 实施计划：第18节 CLI 课程补齐

**分支**：`codex/006-cli-course-alignment` | **日期**：2026-10-24 | **规格**：[spec.md](spec.md)

**状态**：本计划已获用户确认并实施；正文保留设计时口径，实际代码、追加修复与工程结果见 tasks.md / validation.md。

## 摘要

在US2 `edf49bf` 基线上引入Picocli命令树，提供12个叶命令和独立轻路径；增加有界REPL及持久三元组→UUID绑定。会话继续通过AgentService → MemoryService → SessionStore访问，V3历史和审计结构保留，新库版本为V4。终端默认交互、管道保留US2单次JSON；完整Web业务和多通道延期。

用户已批准的三项差异见[澄清记录](spec.md#澄清记录)，逐页映射见[课程追溯](course-traceability.md)。这不是长期Memory的US3，也不扩充九模块。

## 技术上下文

| 项 | 设计 |
| --- | --- |
| 语言/构建 | Java21、Maven九模块，版本沿用根POM |
| 依赖 | Boot4.1.1、Spring AI2.0.1、现有Hibernate/SQLite版本；新增`info.picocli:picocli:4.7.7`，仅cli直接使用，根依赖管理固定版本 |
| 存储 | SQLite V4新增session_bindings；不改V3历史脚本，不新增messages_json，不引入自动DDL |
| 测试 | JUnit5/AssertJ、生产Spring装配、SQLite临时库、打包JAR子进程；真实模型默认关闭 |
| 平台 | Linux为生产正文正向验收平台；macOS核验拒绝契约及适用CLI行为，不增加SDS弱化回退 |
| 类型 | CLI/运行时增量；serve只复用Web健康/OpenAPI基础，无新Agent HTTP接口 |
| 性能/规模 | 单工作区独占写运行时、串行交互；轻命令零Spring运行时、零外呼，以结构和副作用测试判定，不虚构毫秒目标 |
| 上限 | 字节预算沿用US2；身份字段及查询分页上限见[data-model.md](data-model.md) |
| 明确排除 | 完整Web、多IM、长期Memory、完整Tool、通知/调度/MCP、配置热更新、自动迁移个人库、无授权模型调用 |

Picocli版本的公开POM已从Maven Central经TLS核验可获取；这只证明制品存在，不代替实施时依赖解析、兼容性和漏洞扫描。研究依据见[research.md](research.md)。

## 宪法检查

| 原则 | 研究前 | 设计后依据 |
| --- | --- | --- |
| 九模块/依赖朝向core | 通过 | core仅契约；memory门面；storage适配；boot装配；cli不依赖boot实现 |
| Provider显式/ReAct归属 | 通过 | CLI只调用AgentService；不改路由、有限重试和Spring AI自动工具禁用 |
| Memory/Sandbox统一 | 通过 | 新getOrCreate委托MemoryService；已有ReAct/http_get链路保留 |
| 每故事测试与构建 | 通过 | 四故事均有测试及Maven test/package；依赖故事须等前置门禁 |
| 敏感信息/持久审计 | 通过 | 身份/配置/输出白名单；拒绝密钥；不解析并回显原始Picocli错误；审计先行 |
| 增量与中文 | 通过 | 新006承载本轮证据，002/003/005历史保留；YAML/中文文档/关键中文注释 |

上述是设计符合性，工程测试/安全门禁尚未运行。无宪法豁免。

## 项目结构与职责

```text
specs/006-cli-course-alignment/
  spec.md / course-traceability.md / checklists/requirements.md
  plan.md / research.md / data-model.md
  contracts/cli.md / contracts/session.md
  quickstart.md / tasks.md / validation.md

aegisos-core/src/main/java/com/aegisos/core/
  memory/SessionIdentity.java, SessionSummary.java, SessionQuery.java
  memory/SessionStore.java, SessionCatalog.java
  agent/AgentService.java
aegisos-memory/src/main/java/com/aegisos/memory/DefaultMemoryService.java
aegisos-storage/src/main/java/com/aegisos/storage/
  session/JpaSessionStore.java, ReadOnlySessionCatalog.java
  audit/AuditSchemaInitializer.java, AuditStorageConfiguration.java
  runtime/RuntimeDatabaseLock.java
aegisos-storage/src/main/resources/db/
  schema/sqlite/V004__cli_session_bindings.sql
  migration/sqlite/V004__cli_session_bindings.sql
aegisos-cli/src/main/java/com/aegisos/cli/
  AegisOsCli.java, CliArguments.java, CliServices.java
  command/（12个叶命令声明）, ChatCommand.java
aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/
  CliChannel.java, ReplChannel.java
aegisos-boot/src/main/java/com/aegisos/boot/
  AegisOsApplication.java
  config/CliApplicationConfiguration.java, CliWorkspaceConfiguration.java
  cli/DefaultCliServices.java, WorkspaceMetadataService.java
  cli/ProfileDefinitionService.java, CliRuntimeLauncher.java
scripts/verify-cli-package.py（扩展现有进程验收）
scripts/verify-cli-migration.py（新增临时库迁移/重开核验）
docs/engineering/cli-usage.md（实施后用户指南）
docs/engineering/006-cli-course-alignment-changes-review-verification.md（实施收尾）
```

树内新增路径是计划路径，不表示文件已实现。provider/tool/web无需扩业务；它们的安全目录元数据由boot组合到cli端口，不能跨边界读取内部数据库或复制业务引擎。

## 研究与关键决策

1. **轻重分流**：先Picocli解析/帮助，再决定是否启动Spring。root `--help/--version`必须先分流；无参数继续既有Web启动，兼容已记录的单参数`--spring.profiles.active=dev`或`--spring.profiles.active=metrics`旧Web路径，其他未知root选项非零拒绝，不把任意`--...`当Web。
2. **元数据路径**：重用安全YAML、ProfileLoader和Provider定义校验，构建无网络客户端的元数据快照。配置有效和连接可用是不同状态，未探测不得称已连接。轻路径禁止导入ProviderConfiguration/AuditStorageConfiguration。
3. **会话绑定**：新增集中SessionIdentity和原子getOrCreate；同短事务创建UUID会话和绑定，唯一约束防并发，冲突安全重读，不做无界重试；旧UUID保留未绑定。详见[会话契约](contracts/session.md)。
4. **V4**：保留V1～V3脚本及旧迁移测试，新增V4完整建表和V3→V4离线迁移。更新精确DDL集合校验与版本门槛；非空V1/V2/V3一律要求手工迁移，未知/伪V4拒绝。
5. **只读查询**：session list使用独立SessionCatalog适配器，不导入初始化或recovery；只读连接、query_only与既有sidecar锁的非创建模式，不能与写运行时同时持有。缺库/忙/无法证明只读时返回明确状态，不创建任何文件。WAL/SHM存在且无法保证零写入则拒绝，不用immutable绕过锁。
6. **交互**：TTY注入可测的TerminalDetector；明确`--interactive/--batch`优先，未知TTY按单次处理。REPL按字节逐行读取，拒绝超限/坏UTF-8；失败不隐藏继续/重发，终止本次chat。首条quit/EOF不建会话；打开运行时可能按既有规则建库，不将“零处理”冒称“零初始化”。
7. **兼容**：batch无session仍创建独立UUID，保持旧语义；interactive无session时使用`(cli, --user或local, profile)`绑定。显式session仅恢复，不新建绑定；两个Profile别名同时出现拒绝，工作区仍必填绝对路径，不另增配置默认值。
8. **管理与模式**：Profile只读/创建/删除离线执行；定义删除采用安全、可恢复的同文件系统移出agents目录，不递归跟随链接。serve使用工作区快照及现有Web基础（显式端口/监听策略见CLI契约）；gateway非零未启用，不启动运行时。

## 设计阶段产物

- [research.md](research.md)：源码事实、方案、替代项及风险。
- [data-model.md](data-model.md)：身份、绑定、查询、状态和迁移不变量。
- [CLI契约](contracts/cli.md)、[会话契约](contracts/session.md)：参数、输出、安全、事务及兼容行为。
- [quickstart.md](quickstart.md)：实施后可复跑的临时工作区/聚焦/Maven/进程验收方法；所有新命令均标待实现。

## 实施顺序与完成门槛

基础命令/装配端口 → 故事1命令发现和初始化 → 故事3会话绑定与V4 → 故事2交互 → 故事4能力管理 → 工程门禁/课程验收/独立复审/交付指南。故事编号保持规格原编号，按真实依赖调整实施顺序。

每个故事先写行为测试并记录RED，再实现GREEN，运行聚焦测试、适用Maven test/package。全量quality、secrets、dependencies及Linux正向装配在最终完成前执行；只看非空回答、类名、日志N>0、旧XML不能通过。真实天气须新授权与明确预算；未授权记录NOT_RUN，不沿用US2授权。未完成的新课程真实验收不标通过，工程完成和课程完整验收分别报告。

进入implement前按课程skill展示[tasks.md](tasks.md)及批准差异，等待首次范围确认；本轮不自动commit/push。

## 复杂度跟踪

无宪法违规。V4绑定表用于保持UUID/分表与课程三元组幂等，独立只读适配器用于避免查询触发初始化/恢复；二者都有已识别的源码依据，不能由控制台假数据替代。

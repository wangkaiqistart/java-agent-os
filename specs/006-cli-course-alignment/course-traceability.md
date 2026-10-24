# 第18节课程核查与验收追溯

**日期**：2026-10-24。**状态**：实施及工程验收中；下表保留设计时缺口，实际交付结果见末尾实施映射与validation。
**来源**：[第18节PDF](../../docs/第18节：CLI%20功能概述、实现思路与代码讲解.pdf)，7页；**规格**：[spec.md](spec.md)。课件示意代码不作为执行指令。

## 基线

初始工作区为`main@75fb026`，只有本节PDF已暂存。US2实现和课程skill位于`codex/us2-react-loop@edf49bf`；新工作分支`codex/006-cli-course-alignment`从该归档提交创建。保留PDF原暂存blob、003的150项任务及002/005历史，不合并main或修改归档分支。US2旧334项测试及天气PASS不自动作为本节新证据。

## 设计基线快照：课程 → 技术方案 → 原实现/缺口 → FR/SC → 验收

| 条目 | PDF页码/要求 | 技术方案 | 真实实现及缺口 | FR / SC | 验收场景与状态 |
| --- | --- | --- | --- | --- | --- |
| CLI18-01 | p1、5、7：统一入口、12命令与帮助 | §8.7、10 | [boot入口](../../aegisos-boot/src/main/java/com/aegisos/boot/AegisOsApplication.java)仅init/chat；缺命令树与其余10个叶命令 | FR-001～003、025、030 / SC-001、006 | 故事1-3/4、4-3；命令/帮助/退出/真实能力矩阵，按批准范围验收 |
| CLI18-02 | p2：CLI只读、转交、打印 | §8.4 | [ChatCommand](../../aegisos-cli/src/main/java/com/aegisos/cli/ChatCommand.java)已调用统一AgentService，扩展不得复制引擎 | FR-009～010、021 / SC-002、005 | 故事2-1；生产装配两次处理、审计关联，旧003证据只作复用 |
| CLI18-03 | p2～3、7：轻命令不启Spring | §8.6～8.7、14 | init/chat已有分流；新增帮助、配置与状态命令需按实际依赖分类 | FR-002、004～005、020 / SC-001、007 | 故事1-1/3；真实进程启动/外呼/写入检查，不仅看耗时或banner |
| CLI18-04 | p3～4：Picocli逐命令声明 | §8.7 | [CliArguments](../../aegisos-cli/src/main/java/com/aegisos/cli/CliArguments.java)为JDK固定解析器，非Picocli | FR-001～003、014、024 / SC-001、005 | 故事1-3/4、2-5；新增解析与旧调用回归，具体实现留plan |
| CLI18-05 | p3、5、7：显式跨模块实体/仓储装配 | §9.2、10 | [AuditStorageConfiguration](../../aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditStorageConfiguration.java)已有生产装配，复用实际存储契约 | FR-018～020 / SC-004、007 | 故事3-2/4；保存、关闭重开回读；日志N>0不能单独通过 |
| CLI18-06 | p3～4、6：多轮chat、同会话、/quit | §8.4、8.6 | [CliChannel](../../aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/CliChannel.java)是stdin到EOF单次JSON，无REPL | FR-009～015、023、031 / SC-002、005 | 故事2-1～5；两条+/quit/EOF、模式兼容与零额外调用，终端/管道分流已批准 |
| CLI18-07 | p4～6：统一三元组getOrCreate/get/save | §9.2 | [AgentService](../../aegisos-core/src/main/java/com/aegisos/core/agent/AgentService.java)为UUID创建/显式恢复；[SessionStore](../../aegisos-core/src/main/java/com/aegisos/core/memory/SessionStore.java)无三元组绑定 | FR-016～019、029 / SC-003～005 | 故事3-1/3；逐维隔离与并发首次创建，三元组绑定已批准，待实施 |
| CLI18-08 | p4～6：messages_json、手工DDL和重启回读 | §9.2 | [V3 schema](../../aegisos-storage/src/main/resources/db/schema/sqlite/V003__react_runtime.sql)、[JpaSessionStore](../../aegisos-storage/src/main/java/com/aegisos/storage/session/JpaSessionStore.java)已有分表/交换组/短事务，与课程列形式不同 | FR-018～020、029 / SC-004 | 故事3-2/4；旧行/关联/升级恢复，保留分表的偏差已批准；行为等价不代表课程列存在 |
| CLI18-09 | p5：轻量Profile目录查询 | §8.2、8.7 | [ProfileLoader](../../aegisos-boot/src/main/java/com/aegisos/boot/config/ProfileLoader.java)唯一Agent内profile.yml；不采用课程旧目录 | FR-006～008、013、022 / SC-001、005 | 故事1-2、4-1/2；安全YAML、坏项隔离、路径/删除保护 |
| CLI18-10 | p5、7：serve/gateway，共享配置和存储 | §7、8.6 | Web基础/健康已有；完整业务API和多通道未交付 | FR-008、021、024～025、030 / SC-001、004、006 | 故事4-3/4；未来能力未启用不能记三模式通过，按批准范围验收 |
| CLI18-11 | p5～6：会话幂等/隔离/持久化harness | §9.2、13 | 已有[持久化](../../aegisos-boot/src/test/java/com/aegisos/boot/react/SessionPersistenceTest.java)、[并发](../../aegisos-boot/src/test/java/com/aegisos/boot/react/SessionConcurrencyTest.java)、[恢复](../../aegisos-boot/src/test/java/com/aegisos/boot/react/RuntimeRecoveryTest.java)测试，缺三元组断言 | FR-017～019、026～028 / SC-003～007 | 增加本节身份断言、复用既有恢复/审计；同名测试类或旧XML不算本节PASS |
| CLI18-12 | p6～7：帮助/分流、天气对话、持久回读、切换模式 | §12.1、13 | [CLI装配](../../aegisos-boot/src/test/java/com/aegisos/boot/react/CliRuntimeWiringTest.java)、[CLI对话](../../aegisos-boot/src/test/java/com/aegisos/boot/react/CliConversationTest.java)及003真实证据已存在；新增交互/命令矩阵未执行 | FR-026～028 / SC-001～007 | 自动/进程/人工/真实分别记录；天气外呼另授权，未执行=NOT_RUN |

12个叶命令按p1计数：`init`、`status`、`chat`、`serve`、`gateway`、`profile list/create/show/delete`（4个）、`provider list`、`tool list`、`session list`。命令组不另计；技术方案§10后续第13命令不纳入本节。

## 已确定的基线适配

| 差异 | 本项目口径及依据 |
| --- | --- |
| 旧名称/旧profiles目录 | 新产物使用AegisOS及`.aegisos/agents/<dir>/profile.yml`，沿用既有改名与005批准设计，原PDF不修改 |
| 课件Boot3.x | 保持Java21/九模块/Boot4.1.1/Spring AI2.0.1，根POM和宪法优先，不因课件降级 |
| 特定JPA注解示例 | 核验真实跨模块装配及回读，注解/实体实现留plan；已有生产路径不重造 |
| Scanner/直接打印示例 | 沿用字节上限、严格编码、敏感拒绝、前置审计、Sandbox、SDS、锁与安全输出 |
| 两个课程测试类名 | 按行为追溯复用现有测试并补缺口；类名存在不代表执行，人工/真实验收单列 |

## 三项已批准差异（2026-10-24）

1. **Q1／FR-029**：集中增加持久三元组→UUID绑定，保留V3分表、审计与恢复。幂等/隔离/持久行为仍须验收；不新增messages_json，不声称满足该实列断言。旧UUID会话无自动身份回填。
2. **Q2／FR-030**：全部12命令可发现；serve仅现有Web基础，gateway明确未启用且非零退出。完整Web业务、多通道和完整三模式切换延期；此延期不能被“入口存在”消除。
3. **Q3／FR-031**：终端默认交互、管道默认单次JSON，提供互斥显式模式开关；保留--agent，新增--profile别名；别名同时出现拒绝。

上述为设计阶段状态快照，三项批准并非行为PASS；实施状态以下述新增记录为准。任务确认只在具备可审查任务时发生，不重复请求US2已有授权。

## 设计时的实施任务入口（原始范围）

| 条目 | 任务（全为待实施） |
| --- | --- |
| CLI18-01 | T004～018、T055～056 |
| CLI18-02 | T025、T035～043 |
| CLI18-03 | T004～008、T013、T016～017、T047～051 |
| CLI18-04 | T002、T006、T009、T012、T034、T038 |
| CLI18-05 | T022、T028～032、T035、T040、T043 |
| CLI18-06 | T033～045 |
| CLI18-07 | T019～025、T027、T031～032 |
| CLI18-08 | T021、T022、T026、T028～030；messages_json列偏差保留 |
| CLI18-09 | T010、T013～014、T046、T052～053 |
| CLI18-10 | T048、T055～056；完整Web/多通道延期 |
| CLI18-11 | T019～022、T030～032 |
| CLI18-12 | T017、T042～045、T056、T059～068；真实天气未授权 |


## 实施后的课程条目与验证入口

以下复用上表同一条目的FR/SC和验收故事，不改写原缺口历史。自动测试使用临时库/回环替身；真实天气单列。

| 条目 | 实际交付与测试入口 | 验收判定方式 |
| --- | --- | --- |
| CLI18-01 | AegisOsCli / CliArguments / CommandOptions；CliCommandTreeTest、CliDispatchTest；12叶JAR帮助 | 命令存在、参数模型与实际拒绝一致；gateway明确未启用 |
| CLI18-02 | ChatCommand → AgentService → MemoryService；CliReplWiringTest、runtime进程harness | 两处理同会话、持久审计/历史、失败无隐藏重试 |
| CLI18-03 | WorkspaceMetadataService / DefaultCliServices；CliLightPathTest、CliMetadataTest、package strace | 轻路径零Spring运行时/零外呼/无新库；macOS系统调用证明NOT_RUN，Linux单列 |
| CLI18-04 | Picocli4.7.7；CliCommandTreeTest、ChatModeTest | 12叶help、必填、别名互斥、重复/未知/跨命令选项拒绝 |
| CLI18-05 | CliRuntimeLauncher / AuditStorageConfiguration；CliSessionPersistenceTest、CliReplWiringTest | 实际生产Spring装配、关闭重开逐列回读，非仅mock |
| CLI18-06 | ReplChannel；ReplChannelTest、CliReplWiringTest、真实PTY/pipe探针 | 多行/空行/CRLF/EOF/quit、字节/编码/输出故障、安全拒绝和单次七字段兼容 |
| CLI18-07 | SessionIdentity、Memory/Store统一getOrCreate、V4绑定；SessionBindingTest | 逐维隔离、并发单绑定单UUID、无孤儿、旧UUID不自动认领 |
| CLI18-08 | V4新库+离线SQL；CliSchemaMigrationTest、CliSessionPersistenceTest、verify-cli-migration.py | 1006消息/503模型审计/1工具审计/完整交换与全表投影保留；messages_json实列为批准偏差 |
| CLI18-09 | 安全YAML复用、ProfileDefinitionService；CliMetadataTest、ProfileDefinitionTest、CRUD进程矩阵 | 坏项隔离、不回显原文、不覆盖、原子移出、技能/历史不变；沿用agents目录 |
| CLI18-10 | serve专用Web基础装配 / gateway6；CliCapabilityTest、进程启停健康/锁忙/重开 | 批准范围内验证；完整Web业务/多通道/三模式对话延期，不记完整课程PASS |
| CLI18-11 | SessionBindingTest、CliSessionPersistenceTest、既有US2恢复/并发测试 | 新鲜本轮XML；1000读取窗口不截断存档，关闭重开无模型/工具外呼 |
| CLI18-12 | verify-cli-package.py/runtime.py/migration.py、最终Maven/质量/安全/独立复核 | 工程验收分平台记录；T076第18节真实CLI天气PASS，3模型发送/1GET/零重试，独立只读回读通过 |

最终各命令退出码、报告统计及复审关闭依据见[本轮验证](validation.md)。旧002/003/005验证保持不变；此前真实天气NOT_RUN保留为历史，T076新增独立真实PASS；messages_json偏差与完整模式延期不会因工程或天气验收通过而变成PASS。


### 本轮执行状态

CLI18-01～09及11的批准工程范围已通过本轮测试/新JAR/临时迁移验证；CLI18-03的系统调用证明由Linux完成，macOS该证明NOT_RUN。CLI18-10仅批准的serve基础/停止重开/gateway拒绝通过，完整三模式仍延期；CLI18-12工程及本节真实CLI天气通过，T076使用独立run-id核验天气事实/建议、同会话两处理、quit零外呼及关闭重开；真实打包JAR天气外呼未另跑，PTY/pipe进程层沿用本轮回环验证。全部31项FR/7项SC按批准边界追溯，不把旧课程真实结果计入本节。命令退出码、373项新鲜测试统计和当前安全报告见validation末尾。

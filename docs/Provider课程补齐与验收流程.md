# Provider 课程补齐与验收：Spec Kit 流程

本文说明如何把[第 16 节课程原文](<第16节：Agent Provider 原理解析、实现与代码讲解.md>)变成 AegisOS 可执行、可追溯的补齐规格。课程 Markdown 保留原文；本文件记录项目适配与下一轮工作的入口。

原 `specs/002-provider-abstraction/` 的51项任务及验证历史保持不变。本轮补齐功能为 `specs/005-provider-course-alignment/`，已完成规格、澄清、计划、任务和本地实现验证。真实模型 D-02 已于2026-10-22单次成功通过，最终课程验收及当前门禁结果见验证记录；实时状态及证据见[本轮验证](../specs/005-provider-course-alignment/validation.md)。以下 Spec Kit 命令保留为流程说明，不表示应重复创建功能。

历史说明：最初的PDF转录阶段仅转换文档与图片，没有创建功能或执行模型调用；后续005补齐的实际状态以上方验证记录为准。

## 1. 建立课程条目与项目决策

| 课程条目 | 原 PDF 页码 | 本次补齐需要形成的验收要求 |
|---|---|---|
| PDF16-01：Profile 全字段与启动加载 | 3～4、6～7 | Profile 可解析与按名查找；坏文件不影响其他合法配置；引用未知 Provider 明确报错；环境引用行为有测试 |
| PDF16-02：全局连接配置与 Agent 调用参数分层 | 4 | 全局 Provider 连接定义与 Agent 的模型/温度选择分离；准确的来源和覆盖顺序有测试 |
| PDF16-03：会话关联 | 5 | Provider 调用和成功、失败审计均携带 sessionId；requestId、attemptNo 继续标识逻辑请求与尝试 |
| PDF16-04：审计字段与手工迁移 | 5～8 | 明确 success/error_message 与现有 status/error_code 的取舍；手工脚本、实体和读写验证一致；失败信息必须安全 |
| PDF16-05：工具 schema 完整翻译 | 7～8 | 名称、描述、属性、required、嵌套结构等已承诺支持的字段逐项对齐；只翻译、不执行，结构化调用正确返回 |
| PDF16-06：真实模型冒烟 | 6、8 | 显式启用后真实调用一次，断言非空响应和一条成功终态审计；默认构建及 CI 不调用真实模型 |
| PDF16-07：既有 Provider 行为回归 | 2、6～8 | 双 Provider 不串台、未知名称拒绝、成功/失败审计、关闭自动工具执行、环境凭据、依赖解析保持通过 |

PDF16-03 应区分多次逻辑请求与同一请求的多次尝试；不得用 sessionId 替换 requestId，或使同一会话的审计互相覆盖。

## 2. 先 specify，再 clarify

以下为本功能已使用的规格输入示例（无需重复创建）：

```text
$speckit-specify

为 AegisOS 新建 provider-course-alignment 补齐功能。
以 docs/第16节：Agent Provider 原理解析、实现与代码讲解.md 为课程来源，
以 docs/Provider课程补齐与验收流程.md 的 PDF16-01～PDF16-07 为追溯入口，
复用 specs/002-provider-abstraction 的实现及验证记录。

补齐 Profile 加载和注册、两层配置、sessionId 审计关联、审计字段适配、
Tool schema 完整字段断言，以及默认关闭的真实模型冒烟测试。
为每个课程条目建立“PDF 页码/条目 → FR/SC → 验收场景”的对应关系。

保持 Java 21、九模块、已批准的 Boot 4.1.1 / Spring AI 2.0.1 基线，
保留显式路由、有限重试、前置审计门槛、敏感信息保护和已有测试。
先列出课程与项目现行设计冲突，留给 clarify 明确，不能静默选边。
当前只产出补齐规格，不修改 Java，不覆盖原 51 项任务与验证历史。
全部功能产物尽量使用中文。
```

规格创建后执行：

```text
$speckit-clarify
```

以下取舍必须明确写入规格和后续契约：

1. **配置来源（已选A）**：Agent 目录内独立 profile.yml，AGENT.md 保存任务指令，无第二来源或frontmatter回退。
2. **生效时机（已选A）**：全局 Provider、Profile 及技能绑定启动/重启快照，改动需重启，无每轮重新加载。
3. **字段范围（已选B）**：skills 从受控相对软连接派生；notify_channels 只是安全名称引用。本轮不执行技能、通知、MCP、调度或Bootstrap读取。
4. **审计兼容（已选A）**：实际新增 nullable success/error_message，保留 status/error_code；失败说明固定脱敏，V1停机备份后手工迁移，新版启动不自动升级。
5. **会话身份（已选A）**：新入口明确提供受限格式sessionId，仅用于追踪、不查会话表；旧入口明确未关联。requestId和attemptNo继续标识请求/尝试，非法身份不入库。

上述已批准选择取代最初待澄清输入，不再作为未决项。

## 3. plan：把要求落到接口、迁移与测试结构

```text
$speckit-plan
```

计划至少应包含：

- Profile/Agent 加载与注册的模块归属，及其到 Provider 请求的转换路径。
- sessionId 如何经过请求、审计端口、JPA 实体与数据库，和原 requestId/attemptNo 的关系。
- 版本化数据库迁移、已有审计数据保留策略，以及新旧字段约束。
- schema 翻译的字段断言、不可执行性回归及失败分类。
- 普通测试与真实厂商冒烟的发现、筛选和运行方式。
- 每条 PDF 要求到实际测试、命令、证据位置的对应表。

课程中的 `ProviderSmokeIT` 加 `mvn test -Dgroups=integration` 是示意，不能直接当作当前可用入口：Surefire 默认通常不发现 `*IT`。005 已选择 boot 独立 real-provider-smoke profile + Failsafe 3.5.2 + ProviderSmokeIT/real-provider 标签；默认Surefire不发现IT。显式入口确实发现1测试，缺条件时零发送并NOT_RUN非零退出。不能把 0 tests、跳过测试或只有测试类存在当成冒烟通过。

## 4. tasks → analyze：先拆任务，再检查遗漏

```text
$speckit-tasks
$speckit-analyze
```

`tasks` 根据补齐功能的规格与计划生成任务；需要包含先失败、再实现、再通过的测试步骤，以及真实模型冒烟的单独任务。

`analyze` 核对 spec/plan/tasks 的一致性和覆盖：每个 PDF16 条目都应关联验收与任务；课程偏差必须有明确决策。先处理阻断项，再进入实施。

## 5. implement：按任务开发并验证

```text
$speckit-implement
```

实施应先完成默认可复现的测试，再在明确的真实调用授权和厂商凭据具备后执行真实冒烟。保存真实响应非空、审计新增行及安全检查的证据，不采集完整敏感正文。缺凭据或未执行时记录 NOT_RUN，保留对应任务未完成；本地 stub、NVD 扫描或打包成功均不能替代真实模型连通。

默认回归继续使用已存在的入口：

```bash
mvn test
mvn clean package
bash scripts/check.sh quality
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

真实冒烟命令由本轮 plan/实现确定，当前尚未交付，不在此虚构可运行命令。密钥通过受控环境注入，不放入命令参数或配置正文。

## 6. converge：实施后检查尚未完成的要求

```text
$speckit-converge
```

`converge` 以功能的 spec/plan/tasks 为意图来源，只追加尚未完成的任务，不修改规格和计划。因此不能在补齐规格之前，直接要求它根据 PDF 增加 Profile 或其他现有规格明确排除的能力。

若追加任务，继续执行：

```text
$speckit-implement
$speckit-converge
```

`converge` 用于检查代码与功能产物的一致性。真实冒烟是否实际执行，还要读取本轮验收记录；不能仅凭其“已收敛”结论把 NOT_RUN 改成 PASS。

## 7. 最终按矩阵验收

最终验证记录应至少包含以下列：

| 课程条目/页码 | FR/SC | 项目适配或批准偏差 | 测试/任务 | 实际执行命令 | 预期与实际 | PASS/FAIL/NOT_RUN | 脱敏证据 |
|---|---|---|---|---|---|---|---|
| PDF16-01～PDF16-07 | 本轮生成后填写 | 本轮澄清后填写 | 实际类名及任务 ID | 实际可运行命令 | 结果与断言 | 按证据判定 | 报告、查询与时间 |

完成口径为：课程要求已进入规格；差异已经明确决定；默认测试和构建通过；真实模型冒烟已执行且成功审计落库；安全门禁通过；没有未解释的缺项。课程原样要求与项目等价替代分别标明，不把“按原 US-1 规格完成”继续当作“课程全部验收完成”。

## 本轮可复现入口与验收状态

- 配置/迁移/聚焦命令：[005 quickstart](../specs/005-provider-course-alignment/quickstart.md)。
- 真实入口：`mvn -pl aegisos-boot -am -Preal-provider-smoke -Dit.test=ProviderSmokeIT clean verify`；凭据只从环境继承，专用 YAML 由 AEGISOS_SMOKE_CONFIG 显式指定。
- 报告核验：`python3 scripts/verify-provider-smoke.py --reports aegisos-boot/target/failsafe-reports --evidence aegisos-boot/target/provider-smoke-result.json`；外层必须保留 Maven 退出码，可额外传 --maven-exit-code。
- 实际课程追溯、批准替代与当前安全门禁以005 validation为准；本地stub、原91测试或旧NVD结果均不替代真实D-02。课程转录Markdown及原图保持原文，不根据项目适配改写来源。

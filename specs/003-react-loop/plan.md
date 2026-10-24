# 实施计划：US2 ReAct 循环与课程第 17 节对齐

**功能标识**：`003-react-loop` | **实际 Git 分支**：`main` | **日期**：2026-10-22 | **规格**：[spec.md](spec.md)

**输入**：`specs/003-react-loop/spec.md`、已确认澄清、[课程追溯](course-traceability.md)、当前九模块源码与宪法。

**阶段**：实施及验收完成，141/141项。macOS/Linux各87套件297测试、完整工程门禁和Linux真实JAR探针PASS。批准A路线后的独立真实天气验收PASS：3模型发送/1天气GET、零重试，同会话两处理完成；首次FAIL保留，详见 [validation.md](validation.md) 与 [兼容记录](deepseek-compatibility-gap.md)。T133～T135已完成；未提交/推送、迁移个人库或自动进入后续故事。setup-plan的BRANCH=003-react-loop为功能定位值，实际Git分支仍为main。

## 摘要（Summary）

2026-10-22真实失败后的批准修订：采用A路线，新增FR-039。Provider配置增加类型受限的可选thinking-mode（仅disabled；缺省不传扩展），启动时冻结在ProviderDefinition；OpenAI mapper在每次请求显式生成thinking扩展，保持显式路由与零SDK自动工具。CLI YAML白名单/值校验同步，非法配置不能解析凭据或发起请求。公共core消息与V3 schema不变，不引入reasoning正文。专用Java/Python验收配置均强制disabled并将其纳入非秘密指纹；新增A16离线wire/配置回归。追加T136～T141先于新的T133，原135项及失败历史保留；真实验收仍需新的授权。

在既有 US1/005 的显式 Profile/Provider 路由、有限重试与前置审计上，增加项目自控的 ReAct 循环、统一 Memory 门面下的持久会话、逐次文件上下文、唯一 Sandbox 工具执行边界和最小 http_get，并提供非 Web 的 init/chat CLI。课程第17节的五组测试、末轮停止/ThreadLocal回归和真实天气对话均有独立验收入口。

每个用户请求有 processId，每个模型逻辑轮次有 requestId，物理重试保留 attemptNo；先可靠提交审计再允许外部动作。默认10轮、最近20个已完成用户处理历史，部署必填UTF-8逻辑容量预算；末轮含工具只保存并停止。会话可跨重启读取，遗留处理标中断，不自动续跑或重放。工具只实现http_get，其余工具、长期后端、Web业务接口、调度通知不纳入。

## 技术背景（Technical Context）

| 项目 | 采用值与边界 |
| --- | --- |
| Language/Version | Java 21；Maven 3.9.x reactor，固定九模块 |
| Primary Dependencies | 已批准 Boot 4.1.1、Spring AI 2.0.1、Jackson 3、SnakeYAML、JPA/SQLite；tool新增由Boot BOM管理的httpclient5（当前5.6.4/httpcore5 5.4.3）与显式Jackson3依赖；不新增独立版本基线/CLI框架 |
| Storage | 当前V2 llm_calls保持原样，新增V3会话/处理/消息组/工具审计/关联表；aegisos_schema为版本真值；旧库只手工离线迁移 |
| Testing | JUnit Jupiter/AssertJ；课程五组行为；临时文件/SQLite/回环服务/可控DNS与Clock；既有Provider回归、CLI/生产装配集成、Python验证器反例 |
| Target Platform | Java21；生产正文读取必须由JDK提供SecureDirectoryStream，正向验收在Linux执行；macOS不支持时明确拒绝，不提供fallback。一个规范DB对应一个运行进程，不支持共享网络文件系统/多节点写入 |
| Project Type | 公共Java领域编排 + Maven模块实现 + 单boot JAR交付；CLI专用非Web装配，无子命令保留既有Web基础启动 |
| Performance Goals | 确定性次数/容量/截止时间边界，不承诺未经测量的吞吐SLA。N轮最多N次逻辑请求，末轮工具0；默认工具DNS/connect/read/总执行2/3/5/10秒 |
| Constraints | 明确Provider/模型；SDK零自动工具；Memory门面；工具唯一Sandbox；单工具不重试/不重定向；敏感内容拒绝；每故事test/package及最终门禁 |
| Scale/Scope | 5个规格故事、39个FR、12个SC、10个课程条目；仅http_get生产工具；最多8并发工具许可、4在途DNS；真实验收独立最多6模型发送/2天气GET |

待研究项曾包括来源目录映射、会话恢复与锁、模型处理关联、字节计量、重复工具ID适配、DNS固定、CLI启动和严格冒烟证据，已由 [research.md](research.md) R01～R13 全部解决；无未决技术占位。平台/依赖行为以实施时聚焦测试核实，不把研究结果当构建通过。

## 宪法检查（Constitution Check）

预研究检查与设计完成后复查均通过**设计门禁**，不代表实现/测试已通过。

| 原则 | 研究前约束 | Phase 1 复查 |
| --- | --- | --- |
| 九模块与依赖方向 | core不能访问Provider/存储实现 | 契约/编排在core，其余模块向core；boot接线，无新模块、无环 |
| 显式Provider、自实现ReAct | 复用带会话公共入口、唯一Provider边界 | 兼容四参数Profile重载只加显式关联，仍同execute/audit/retry；wire ID不放宽原mapper；无SDK执行工具 |
| Memory统一门面 | Session/长期记忆不散落循环 | MemoryService(core契约)、DefaultMemoryService(memory)、存储端口实现(storage)；长期默认空，不提前交付US3 |
| 所有Tool经Sandbox | 不只检查名称白名单 | 唯一执行器含身份/参数/地址/时间/资源/审计；http_get固定DNS与目标，明确不等于OS隔离 |
| 每故事可测与构建 | 每故事聚焦测试和Maven test/package | 五组课程测试+存储/CLI集成；A01～A16、全部FR/SC有映射，失败阻断依赖增量 |
| 敏感保护与持久审计 | 禁止秘密入库/日志，先提交再动作 | 各内容边界拒绝已知秘密；工具/模型真实审计与必要对话状态分开；不伪造已发生事实 |
| 中文/YAML/增量范围 | 中文产物、YAML、关键代码详细中文注释 | 本计划及契约中文；原002/005历史不覆盖，个人库不迁移，真实测试默认关闭 |

无需要豁免的宪法冲突。宪法的长期Memory默认MEMORY.md口径与本轮会话SQLite并不冲突：会话持久化是US2明确范围，长期后端本轮未实现。课程/旧技术方案差异已在规格批准，本计划不静默反转。

## 工程结构（Project Structure）

### 本功能文档

```text
specs/003-react-loop/
├── spec.md                         # 既有规格/澄清，不覆盖历史决定
├── course-traceability.md           # PDF17-01～10到需求/场景/设计
├── checklists/requirements.md       # 既有规格质量清单
├── plan.md                         # 本计划
├── research.md                     # Phase 0决策
├── data-model.md                   # Phase 1实体/事务/迁移/恢复
├── quickstart.md                   # 实施后的验证步骤与当前限制
├── tasks.md                        # 141项（原135项+批准A路线6项），按实际证据标记完成
├── validation.md                   # 本轮执行结果和未通过/未执行范围
├── architecture-review.md          # 本轮架构复核及修复证据
└── contracts/
    ├── agent-memory-provider.md
    ├── context.md
    ├── tools-sandbox.md
    ├── configuration-cli.md
    └── acceptance.md
```

tasks.md由speckit-tasks生成并在实施中增量勾选；validation.md保留各阶段RED/GREEN、门禁结果与NOT_RUN。课程追溯和验收契约的设计映射继续保留，不把离线通过改称真实天气通过。

### 源码归属（本轮实现）

```text
aegisos-core/src/{main,test}/java/com/aegisos/core/
  agent/       AgentService、ReActLoop、ProfileContext、处理DTO
  context/     PromptBuilder、ContextLoader、预算/敏感检查/来源契约
  memory/      MemoryService、SessionStore、LongTermMemoryStore、会话/组DTO
  tool/        AegisTool、ToolExecutor、Sandbox、工具结果/注册查询契约
  audit/       ToolInvocationAuditStore；兼容扩展AuditStart/Rejection
  provider/    ProviderInvocation、ProfileProviderService兼容重载
aegisos-memory/src/{main,test}/java/com/aegisos/memory/
  DefaultMemoryService、EmptyLongTermMemoryStore
aegisos-tool/src/{main,test}/java/com/aegisos/tool/
  DefaultToolExecutor、ToolRegistry、WhitelistSandbox、HttpGetTool、受控HTTP传输
aegisos-storage/src/{main,test}/java/com/aegisos/storage/
  session/、tool/、runtime/持久实现与锁；audit/关联及schema初始化扩展
aegisos-storage/src/main/resources/db/{schema,migration}/sqlite/
  V003__react_runtime.sql（各目录分别为完整schema与增量迁移）
aegisos-provider/src/{main,test}/java/com/aegisos/provider/
  service/显式关联传递；adapter/有界响应；保留原路由/重试/mapper
aegisos-channel-cli/src/{main,test}/java/com/aegisos/channel/cli/
  CliChannel：有界stdin/安全JSON输出
aegisos-cli/src/{main,test}/java/com/aegisos/cli/
  命令解析、InitCommand、ChatCommand
aegisos-boot/src/main/java/com/aegisos/boot/
  main分流、专用CLI source、显式Runtime配置、Profile来源索引/安全快照
aegisos-boot/src/test/java/com/aegisos/boot/
  会话/事务/上下文/CLI集成；ReactWeatherSmokeIT
scripts/
  verify-react-smoke.py、tests/test_verify_react_smoke.py（计划新增）
aegisos-web/                         # 本轮不新增业务入口
```

**结构决定**：core保持无Spring/JPA/具体HTTP依赖；memory/tool/provider/channel-cli依赖core，storage保持既有core+memory依赖并实现core端口；cli保持既有依赖但只调用core/channel公共契约，boot依赖各实现完成装配。web现有依赖不扩展，不调用CLI，不让tool/provider反向依赖storage。测试所需JUnit/AssertJ在相应模块显式声明，不能依赖偶然传递依赖。

## Phase 0：研究完成

见 [研究决策](research.md)：R01模块、R02～04存储/恢复/关联、R05～06循环和ID、R07～09上下文/预算/敏感、R10～11工具安全/schema、R12 CLI/验收，R13批准A路线显式模式。现有源码核对与三组只读研究已完成；不需要再向用户询问已批准的行为。

## Phase 1：设计与契约

| 设计面 | 选择与交付 |
| --- | --- |
| 运行链 | AgentService→ReActLoop→PromptBuilder→现有ProfileProviderService→唯一ToolExecutor；当前Agent上下文finally清理 |
| Memory/状态 | 全部会话/长期访问经MemoryService；短事务保存关键步骤、最终提交后才能返回完成；同会话忙拒绝 |
| 恢复/迁移 | 数据模型明确V3新增表、独占DB进程锁、RUNNING中断、OPEN组过滤、手工备份迁移/回滚；不自动重放 |
| 模型关联 | 显式ProviderInvocation兼容重载；audit.begin/reject同事务链接；原无处理关联调用继续可用，不回填旧历史 |
| Prompt | 来源索引不猜目录；每次正文重读；必填逻辑UTF-8容量、单项上限、20轮/整组裁剪与wire ID投影 |
| 安全 | 已知秘密拒绝而不替换；已发生网络结果审计不篡改；http_get exact目标、DNS固定、总deadline、无重试/重定向 |
| 用户入口 | init不启动Spring；单次stdin chat用非Web runtime，新建/显式session续聊；无测试HTTP端点 |
| 验收 | [A01～A16](contracts/acceptance.md)，默认替身；显式真实天气最多6模型/2GET，严格XML+新鲜证据+审计重开 |

全部配置键、默认值/必填值、范围及CLI退出码见 [configuration-cli.md](contracts/configuration-cli.md)，不在不同文档定义另一组同名默认值。

## 后续实施与验证次序

以下是增量边界，不是tasks清单：

1. 公共契约/安全与容量/来源索引、V3与Memory基础先落实，并验证依赖DAG、事务/迁移和上下文边界。跨故事共享前置能力同样有聚焦测试。
2. 故事1完成纯文本、持久会话、同会话忙和保存失败；聚焦验收与test/package通过后进入依赖能力。
3. 故事2完成唯一执行边界、工具审计/http_get和去重；故事3收敛精确迭代、关联、中断恢复与上下文清理回归。前者通过后才以其为完成依赖。
4. 故事4补齐完整文件/Skill/长期空端口/历史裁剪/敏感所有入口矩阵；其必要安全基础必须在任何真实调用前通过，不能延后绕过。
5. 故事5装配CLI、离线端到端与默认关闭真实harness；全部故事及质量/秘密/依赖门禁后，再按明确开关和预算执行真实验收。只跑默认测试不能宣布真实天气通过。

运行路径见 [quickstart.md](quickstart.md)。最终保留本功能validation记录、课程追溯与每故事测试/构建证据；不覆盖002的51项任务或005的80项历史。代码应有详细中文注释，重点解释事务提交、末轮边界、ID投影、路径/DNS和敏感检测的原因及限制。

## 复杂度追踪（Complexity Tracking）

没有宪法豁免。新增处理/交换组/模型链接是跨重启恢复、完整消息配对和外呼前关联所必需；DNS固定传输为http_get安全边界所必需。未引入分布式锁、调度/通知、全部长期记忆后端或通用工具框架扩展。

## 设计自检与当前证据

- 已核对：POM九模块与批准版本、当前入口/依赖方向、SQLite版本标识和短事务、Profile来源缺口、Provider重复ID限制、CLI骨架与Failsafe边界。
- 已产出：研究、数据模型、五份契约、验收映射与验证指南；课程条目均可追到设计/计划测试。
- 已静态核验文档结构、相对链接、39项FR/12项SC覆盖、YAML示例与空白格式；只读交叉复核补严了时间锚与非法调用关联。不将这些检查描述为Maven/Java或真实验收通过。
- Phase 1后宪法检查通过，无未决澄清；实施时任何测试/构建/安全门禁失败均继续阻断完成认定。

## 代码评审修复阶段（2026-10-22，追加）

页首141/141及297项测试是原实施/真实验收完成时的历史快照，保留不改写。本次响应随后三个代码评审缺陷，追加 T142～T150 共9项，原任务总量现为150。执行顺序为回归RED→实现GREEN→Maven/平台门禁→独立复审，保留原002/005记录与首次真实FAIL/新版PASS。

当前修订涉及敏感JSON字段完整标量判断、独立sidecar锁与可验证单链接限制、Profile注册前运行策略上界校验。评审中发现的占位前缀/锁文件角色冲突按相同TDD顺序补证，具体结果见 validation.md 的末尾增量；不 commit/push，不运行新的真实外呼，不自动进入后续故事。

阶段完成（2026-10-24）：T142～T150已全部完成，累计150/150。最后macOS test/package/quality与Linux强制SDS验证各88套件/334测试零失败/错误/跳过，独立复审21/21文件未发现剩余新增缺陷；迁移/Python回归与秘密扫描通过。未提交/推送，未新增真实外呼，原141项与旧失败/成功记录保留。

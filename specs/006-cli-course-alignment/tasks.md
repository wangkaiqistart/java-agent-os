# 任务：第18节 CLI 课程补齐

**输入**：[规格](spec.md)、[计划](plan.md)、[研究](research.md)、[数据模型](data-model.md)、[CLI契约](contracts/cli.md)、[会话契约](contracts/session.md)、[验证步骤](quickstart.md)。

**状态**：76/76任务完成（原68项＋追加7项工程任务＋T076真实验收）。本轮真实CLI天气验收PASS：3次模型发送、1次天气GET、重试0，同会话两条消息及重开审计通过。首次实施范围已获用户确认。本表不覆盖003原150项及002/005历史。这里US1～US4指006的四个用户故事，不是项目整体的Provider/ReAct/Memory/Tool编号。

**规则**：测试先行，确认预期行为RED，再实现GREEN；每故事测试/package门禁通过后才进入依赖故事。P只表示文件与依赖可并行，不授权并发Maven/clean或真实外呼。所有新增Java关键逻辑解释中文设计理由；配置YAML。

## 阶段1：准备（T001～T003）

- [x] T001 在 `specs/006-cli-course-alignment/validation.md` 记录实施前HEAD、PDF指纹、工作树/暂存快照、003历史及当前工程基线；保留已有PDF暂存，不以旧334项测试冒称本轮结果。
- [x] T002 在 `pom.xml` 固定Picocli4.7.7并在 `aegisos-cli/pom.xml` 增加依赖，保留Java21/Boot4.1.1/SpringAI2.0.1和九模块，记录真实解析结果。
- [x] T003 在 `specs/006-cli-course-alignment/validation.md` 固定本轮RED/GREEN、Linux/macOS、临时库、进程、质量/安全/真实NOT_RUN的证据索引；凭据与报告不能入Git。

## 阶段2：共用基础（T004～T008）

**目标**：cli依赖core/端口，boot装配实现；帮助与元数据无需完整Spring。

- [x] T004 在 `aegisos-cli/src/test/java/com/aegisos/cli/CliDispatchTest.java` 先补分流回归：root/嵌套help、version不启动Web，未知/重复/原始敏感参数拒绝且零副作用，保存RED。
- [x] T005 在 `aegisos-cli/src/main/java/com/aegisos/cli/CliServices.java` 定义安全元数据、Profile管理和运行时调用端口，参数/返回仅core值对象；禁止cli反向依赖boot/storage内部实现。
- [x] T006 在 `aegisos-cli/src/main/java/com/aegisos/cli/AegisOsCli.java` 建Picocli命令树与固定安全异常/用法handler；在 `aegisos-cli/src/main/java/com/aegisos/cli/CliArguments.java` 保留旧参数兼容并拒绝重复/别名同时出现，保证原始值不入stderr。
- [x] T007 在 `aegisos-boot/src/main/java/com/aegisos/boot/AegisOsApplication.java` 先解析help/version再分流，在 `aegisos-boot/src/main/java/com/aegisos/boot/cli/DefaultCliServices.java` 装配端口；保留无参数Web与旧Spring参数仅--spring.profiles.active=dev或metrics的单参数路径，未知root选项不当Web。
- [x] T008 在 `aegisos-cli/src/test/java/com/aegisos/cli/CliDispatchTest.java` 确认GREEN，并向 `specs/006-cli-course-alignment/validation.md` 写真实发现数/退出码，冻结轻重路径测试断言后开放故事。

## 阶段3：用户故事1——命令发现与初始化（P1，T009～T018）

**独立验收**：无密钥/外网的临时工作区，全部help零外呼/零写；init离线无覆盖；Profile合法项/坏项安全呈现。为MVP，不等于12业务能力全部完成。

### 测试先行

- [x] T009 [P] [US1] 在 `aegisos-cli/src/test/java/com/aegisos/cli/CliCommandTreeTest.java` 补12叶命令及各组帮助、显式绝对workspace/Profile、未知/重复/缺值/别名冲突回归，先记录RED。
- [x] T010 [P] [US1] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliMetadataTest.java` 补无凭据元数据、坏Profile隔离、安全投影、旧目录/frontmatter拒绝、零客户端/零数据库装配回归，先记录RED。
- [x] T011 [US1] 在 `aegisos-cli/src/test/java/com/aegisos/cli/CliCommandTest.java` 扩展init安全目录/CREATE_NEW/失败仅清理新建内容、已有目录不变及零库/凭据/外呼断言，确认既有行为仍受保护。

### 实现与验证

- [x] T012 [US1] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/` 为12叶命令及命令组建立独立声明/用途/能力状态/参数，参数冲突在副作用前拒绝；未来gateway声明未启用不伪造业务成功。
- [x] T013 [US1] 在 `aegisos-boot/src/main/java/com/aegisos/boot/cli/WorkspaceMetadataService.java` 用安全YAML和纯Provider元数据registry复用ProfileLoader/运行策略校验；不导入ProviderConfiguration/AuditStorageConfiguration、不解析真凭据、不称已连接。
- [x] T014 [US1] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/ProfileListCommand.java` 和 `aegisos-cli/src/main/java/com/aegisos/cli/command/ProfileShowCommand.java` 输出白名单字段与固定诊断，坏项不阻断合法项展示、不回显原文件，含坏项退出2。
- [x] T015 [US1] 在 `aegisos-cli/src/main/java/com/aegisos/cli/InitCommand.java` 接入命令服务并保留既有安全模板/离线/无覆盖行为，不新增默认Profile或凭据配置通道。
- [x] T016 [US1] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliLightPathTest.java` 验证help/init/profile查询真实装配不启动完整上下文，目录快照/数据库/sidecar及网络计数符合零副作用。
- [x] T017 [US1] 在 `scripts/verify-cli-package.py` 扩展打包JAR root/12叶help、未知参数及init/Profile只读进程断言，非空输出或无banner不能单独算通过；仅临时工作区。
- [x] T018 [US1] 运行cli/boot相关聚焦测试、适用Maven test及package，核对新鲜XML/实际发现无跳过，将RED/GREEN与命令/码写入 `specs/006-cli-course-alignment/validation.md`；失败阻断后续依赖。

## 阶段4：用户故事3——绑定、迁移和重开（P1，T019～T032）

**顺序理由**：故事2交互依赖三元组恢复，所以先实现故事3；编号仍与规格一致。

**独立验收**：同元组同UUID、逐维不同隔离、并发一个胜者、V3→V4原数据不变、完整历史重开及旧UUID兼容；不依赖REPL。

### 测试先行

- [x] T019 [P] [US3] 在 `aegisos-core/src/test/java/com/aegisos/core/memory/SessionIdentityTest.java` 补“channel/user非空，1～64个ASCII字符，匹配`[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`；profile匹配`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`”、大小写敏感、不trim、分隔符不碰撞反例，保存RED。
- [x] T020 [P] [US3] 在 `aegisos-storage/src/test/java/com/aegisos/storage/session/SessionBindingTest.java` 补相同/逐维/并发首次创建、一次失败全回滚无孤儿、错归属/悬空/重复UUID绑定约束与重读上限回归，保存RED。
- [x] T021 [P] [US3] 在 `aegisos-storage/src/test/java/com/aegisos/storage/runtime/CliSchemaMigrationTest.java` 补V3→V4旧行/关联保留、失败回滚、重复执行/伪V4/旧版本拒绝及新库V4回归，保留旧迁移fixture，保存RED。
- [x] T022 [P] [US3] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliSessionPersistenceTest.java` 补生产装配三元组关闭重开、旧UUID未绑定仍恢复、超过1000条存档/完整工具交换/审计链独立全量比对，保存RED。

### 实现与验证

- [x] T023 [US3] 在 `aegisos-core/src/main/java/com/aegisos/core/memory/SessionIdentity.java` 实现T019值域/不可变/安全异常，并在 `aegisos-core/src/main/java/com/aegisos/core/memory/SessionStore.java` 定义原子getOrCreateSession端口，保留既有方法。
- [x] T024 [US3] 在 `aegisos-memory/src/main/java/com/aegisos/memory/DefaultMemoryService.java` 实现新端口的已知密钥/存取结果校验和统一委托，在 `aegisos-memory/src/test/java/com/aegisos/memory/MemoryServiceTest.java` 验证无法经绑定绕过guard。
- [x] T025 [US3] 在 `aegisos-core/src/main/java/com/aegisos/core/agent/AgentService.java` 暴露身份校验→Profile快照→Memory门面的getOrCreate入口；旧createSession/process与非等待锁保持，未知Profile不写库。
- [x] T026 [US3] 新增 `aegisos-storage/src/main/resources/db/schema/sqlite/V004__cli_session_bindings.sql` 完整schema及 `aegisos-storage/src/main/resources/db/migration/sqlite/V004__cli_session_bindings.sql` V3离线升级；绑定主键三元组、UNIQUE(session_id)、复合外键、UTC created_at非空，保留旧脚本原文。
- [x] T027 [US3] 在 `aegisos-storage/src/main/java/com/aegisos/storage/session/JpaSessionStore.java` 实现会话+绑定同短事务、唯一冲突回滚后最多一次安全重读；命中不写时间戳，不自动绑定旧UUID，不改ReAct/审计事务。
- [x] T028 [US3] 在 `aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditSchemaInitializer.java` 更新新空库V4/版本门槛/全量DDL与索引外键校验；V1/V2/V3非空库要求手工迁移，未知/伪库拒绝，不启动ALTER。
- [x] T029 [US3] 在 `aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditStorageConfiguration.java` 显式接线新增端口并延续独占锁→schema→recovery顺序；更新相关生产装配测试夹具，不能让mock替代数据库回读。
- [x] T030 [US3] 在 `scripts/verify-cli-migration.py` 实现仅临时库的备份/独占锁/V3→V4/重开/恢复与失败演练，比较原表完整投影、序号/审计外键；个人库路径拒绝，不隐藏重跑。
- [x] T031 [US3] 在 `aegisos-boot/src/test/java/com/aegisos/boot/react/SessionConcurrencyTest.java` 和 `aegisos-boot/src/test/java/com/aegisos/boot/react/RuntimeRecoveryTest.java` 验证原非等待锁/恢复不重放仍有效，并记录新增绑定不影响旧处理语义。
- [x] T032 [US3] 运行core/memory/storage/boot聚焦测试、适用Maven test/package及临时库迁移脚本，在 `specs/006-cli-course-alignment/validation.md` 记录RED/GREEN、完整回读与真实装配证据；通过后才放行故事2。

## 阶段5：用户故事2——有界交互与批处理兼容（P1，T033～T045）

**独立验收**：两条消息+/quit恰好两处理，同UUID不同process；PTY/pipe默认正确，七字段JSON兼容；失败无隐藏重发、资源可靠关闭。

### 测试先行

- [x] T033 [P] [US2] 在 `aegisos-channel-cli/src/test/java/com/aegisos/channel/cli/ReplChannelTest.java` 补字节有界逐行、UTF-8/CRLF、无换行EOF、空白忽略、quit零处理、超限/密钥拒绝和输出失败，保存RED。
- [x] T034 [P] [US2] 在 `aegisos-cli/src/test/java/com/aegisos/cli/ChatModeTest.java` 补TTY/pipe/无法判断、互斥开关、alias冲突、--user值域、batch/显式session与user冲突、旧batch新UUID回归，保存RED。
- [x] T035 [P] [US2] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliReplWiringTest.java` 补真实装配两条+/quit同会话、次轮历史/审计关联、首条quit/EOF无会话及异常/中断释放资源回归，保存RED。

### 实现与验证

- [x] T036 [US2] 在 `aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/ReplChannel.java` 实现有界字节行读取/严格编码与安全输出，复用既有预算/guard，不回显用户原输入，不用无界readLine。
- [x] T037 [US2] 在 `aegisos-cli/src/main/java/com/aegisos/cli/ChatCommand.java` 实现串行REPL：首条有效消息再获取绑定、后续保持session，quit/EOF零额外处理，失败非零结束；batch保留原七字段/退出语义及新UUID默认。
- [x] T038 [US2] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/ChatCliCommand.java` 实现--interactive/--batch与--profile/--agent及--session/--user约束，交互默认身份cli/local/Profile，user只是逻辑组，禁止暗取OS用户名。
- [x] T039 [US2] 在 `aegisos-boot/src/main/java/com/aegisos/boot/cli/CliRuntimeLauncher.java` 注入可测TerminalDetector并统一try/finally关闭上下文/传输/池/自有锁；将本地提示写stderr，不污染batch stdout。
- [x] T040 [US2] 在 `aegisos-boot/src/main/java/com/aegisos/boot/config/CliApplicationConfiguration.java` 显式装配新通道/命令，不引入Web扫描/自动工具执行，保持启动快照与正文逐轮安全重读。
- [x] T041 [US2] 在 `aegisos-boot/src/test/java/com/aegisos/boot/react/CliConversationTest.java` 保留US2 stdin到EOF、--agent/--session七字段JSON、未知/错归属拒绝、maxIterations及敏感历史回归；不将batch正文/quit当控制命令。
- [x] T042 [US2] 在 `scripts/verify-cli-package.py` 增加真实PTY默认交互、pipe默认单次、显式开关和两处理/quit/资源释放进程验收，使用回环Provider替身，参数中无真实秘密。
- [x] T043 [US2] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliReplWiringTest.java` 核验Provider/Tool审计关联、前置审计失败零发送、密钥零外泄、同调用不重做、输出失败非零；本地工具目标必须经Sandbox。
- [x] T044 [US2] 在Linux执行正文正向/指令逐轮读取与REPL生产装配，在macOS验证SDS缺失明确拒绝，向 `specs/006-cli-course-alignment/validation.md` 分平台记证据，不新增弱化回退。
- [x] T045 [US2] 运行channel-cli/cli/boot聚焦测试、适用Maven test/package和进程harness，在 `specs/006-cli-course-alignment/validation.md` 记录发现数/退出码/新鲜报告及零额外调用断言；依赖故事失败则停。

## 阶段6：用户故事4——管理与真实能力查询（P2，T046～T058）

**独立验收**：Profile增删不越界/不改历史；只读查询不建库/恢复；serve基础可运行，gateway明确未启用，二者不冒充完整课程三模式。

### 测试先行

- [x] T046 [P] [US4] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/ProfileDefinitionTest.java` 补CREATE_NEW、未知Provider/model、敏感值、重名/软链接/越界、原子移出失败无半删及技能目标/会话审计不变回归，保存RED。
- [x] T047 [P] [US4] 在 `aegisos-storage/src/test/java/com/aegisos/storage/session/ReadOnlySessionCatalogTest.java` 补缺库/缺lock/旧schema/忙/WAL不安全拒绝、RUNNING不恢复及目录/摘要/mtime全不变，query limit默认20范围1～100、offset默认0范围0～100000，保存RED。
- [x] T048 [P] [US4] 在 `aegisos-boot/src/test/java/com/aegisos/boot/cli/CliCapabilityTest.java` 补status/provider/tool真实投影、gateway业务退出6/help0零启动、serve本机Web基础/关闭、原无参数Web兼容，保存RED。

### 实现与验证

- [x] T049 [US4] 在 `aegisos-core/src/main/java/com/aegisos/core/memory/SessionSummary.java`、`SessionQuery.java`、`SessionCatalog.java` 定义安全摘要/分页端口：计数非负long，合法Profile过滤，limit/offset严格按T047，不返回正文/用户身份/工具参数。
- [x] T050 [US4] 在 `aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeDatabaseLock.java` 增加仅打开既有安全sidecar的查询锁路径，保持写者互斥/JVM登记/单硬链接契约；不创建文件、不释放他人锁、不可证明则拒绝。
- [x] T051 [US4] 在 `aegisos-storage/src/main/java/com/aegisos/storage/session/ReadOnlySessionCatalog.java` 实现mode=ro/query_only及规范V4只读校验/安全分页，按updated_at DESC,id ASC稳定排序，无initializer/recovery/DDL；无保证时QUERY_UNAVAILABLE，禁用immutable/读写回退。
- [x] T052 [US4] 在 `aegisos-boot/src/main/java/com/aegisos/boot/cli/ProfileDefinitionService.java` 实现显式Provider/model安全模板创建及原子移到.aegisos/deleted-agents/随机UUID的删除，使用批准根/CREATE_NEW/不覆盖/链接保护；不级联存储、不开热更新。
- [x] T053 [US4] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/ProfileCreateCommand.java` 与 `ProfileDeleteCommand.java` 接线安全结果及下次启动生效说明，不把原始YAML/身份/异常回显。
- [x] T054 [US4] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/StatusCommand.java`、`ProviderListCommand.java`、`ToolListCommand.java`、`SessionListCommand.java` 接线实际元数据与只读端口，区分配置/未探测/不可用，缺库/旧库/忙非零，不伪造空成功或未来9工具全可用。
- [x] T055 [US4] 在 `aegisos-cli/src/main/java/com/aegisos/cli/command/ServeCommand.java` 与 `GatewayCommand.java` 实现Web基础启动/明确禁用；boot launcher仅使用批准workspace及本机127.0.0.1端口1～65535默认8080，不透传任意Spring参数，无新Agent API。
- [x] T056 [US4] 在 `scripts/verify-cli-package.py` 增加12命令能力矩阵、Profile增删、只读查询零写、serve实际监听/健康/停止后chat重开原数据不丢及gateway拒绝的打包进程验收；明确完整三模式延期。
- [x] T057 [US4] 在 `aegisos-storage/src/test/java/com/aegisos/storage/runtime/RuntimeDatabaseLockTest.java` 和 `aegisos-storage/src/test/java/com/aegisos/storage/session/ReadOnlySessionCatalogTest.java` 核验同JVM/跨进程读写竞争、路径/锁所有权/sidecar与查询不恢复，无法验证平台不伪通过。
- [x] T058 [US4] 运行cli/storage/boot相关测试、适用Maven test/package和进程harness，将RED/GREEN/目录快照/重开与退出码写 `specs/006-cli-course-alignment/validation.md`，通过才进入全量收尾。

## 阶段7：跨故事质量与收尾（T059～T068）

- [x] T059 逐项核对 `specs/006-cli-course-alignment/course-traceability.md` 的CLI18-01～12→FR/SC→实际测试/进程/证据，messages_json与完整三模式保留批准偏差，未执行不写PASS。
- [x] T060 串行执行 `mvn -B clean package` 和 `bash scripts/check.sh quality`，核对当前九模块新鲜XML、测试实际发现/失败/错误/跳过、打包与SpotBugs，命令/码写 `specs/006-cli-course-alignment/validation.md`。
- [x] T061 执行 `scripts/check.sh secrets` 的历史/工作树扫描，禁止原始密钥/敏感报告入库，在 `specs/006-cli-course-alignment/validation.md` 记录工具版本和结果。
- [x] T062 执行 `scripts/check.sh dependencies` 的当前依赖扫描（含新增Picocli），仅受控注入NVD凭据；数据源失败也阻断，不复制旧安全PASS，证据写 `specs/006-cli-course-alignment/validation.md`。
- [x] T063 执行Linux新代码副本的全量质量及实际正文/CLI装配、迁移与JAR进程验收，在 `specs/006-cli-course-alignment/validation.md` 区分macOS拒绝与Linux正向证据，不复用旧XML。
- [x] T064 在 `specs/006-cli-course-alignment/validation.md` 记录本节真实天气授权状态；工程门禁后才申请明确run-id/预算，未获授权记NOT_RUN并不得宣称完整真实课程验收通过，失败不自动重跑。
- [x] T065 在 `docs/engineering/cli-usage.md` 编写最终可运行命令、REPL/pipe示例、Profile快照、V4迁移/查询限制、serve/gateway能力与安全拒绝，并同步 `specs/006-cli-course-alignment/quickstart.md` 两脚本完整参数。
- [x] T066 按reviewing-code工作流独立复审实际变更与六类边界，将发现/复现/处置写 `specs/006-cli-course-alignment/validation.md`；新缺陷追加任务、先RED后修复，旧FAIL/PASS不删除。
- [x] T067 生成 `docs/engineering/006-cli-course-alignment-changes-review-verification.md` 的变更总结→重点Review→复跑验证/已有证据/局限，从 `README.md` 提供入口；不得用作者自检冒充独立复审。
- [x] T068 在 `specs/006-cli-course-alignment/validation.md` 最终核对任务/链接/证据/原PDF暂存与旧历史保留；按实际已完成/未执行/延期分别报告，不自动commit/push或迁移个人库。

## 依赖与增量策略

```text
T001～008 共用准备/基础
      ↓
US1 T009～018（MVP：发现、初始化、配置查看）
      ↓
US3 T019～032（绑定/V4/持久化）
      ↓
US2 T033～045（REPL与兼容）
      ↓
US4 T046～058（管理/只读查询/模式边界）
      ↓
T059～068 全量/安全/课程/独立复审/交付
```

单个故事用替身/临时fixture独立验收，不需要真实模型、后续完整Memory/Tool/Web能力。每故事门禁失败阻断其下游；最终依赖扫描失败也不放行。非必要不重复门禁，只有新变更/失败/未决风险才扩大重测。

## 可并行内容（非并发构建授权）

- US1：T009解析契约与T010元数据装配测试分别编写；运行Maven仍串行。
- US3：T019值对象、T020绑定、T021迁移、T022生产重开测试可分文件设计；DDL及生产装配改动串行合入。
- US2：T033通道、T034模式、T035装配测试可分别准备，待US3绿色后执行。
- US4：T046文件管理、T047只读存储、T048能力测试可分别准备；T050锁契约先于T051查询实现。
- T001/T003同写validation.md，必须串行。共享脚本/POM/boot入口变更必须串行；P不意味着可绕过先测后实现。

## 需求覆盖索引

| FR | 任务 |
| --- | --- |
| FR-001 | T006、009、012、017 |
| FR-002 | T004、009、016、017 |
| FR-003 | T004、006、009、034 |
| FR-004 | T007、013、016、048、054 |
| FR-005 | T011、015、017 |
| FR-006 | T010、013、014、052 |
| FR-007 | T046、052、053 |
| FR-008 | T010、048、049、051、054 |
| FR-009 | T025、035、037、040、043 |
| FR-010 | T035、037、043 |
| FR-011 | T033、035、037、039、042 |
| FR-012 | T024、033、036、043 |
| FR-013 | T009、013、034、038 |
| FR-014 | T006、009、034、038、041 |
| FR-015 | T009、015、038 |
| FR-016 | T020、022、025、041 |
| FR-017 | T019、020、023、027 |
| FR-018 | T022、030、031、043 |
| FR-019 | T021、026、028、030 |
| FR-020 | T022、029、035、040 |
| FR-021 | T024、031、043、057、066 |
| FR-022 | T013、040、044、052 |
| FR-023 | T033、034、036、037、041、042 |
| FR-024 | T007、041、048、055、056 |
| FR-025 | T012、048、054、055、056 |
| FR-026 | T001、059、067、068 |
| FR-027 | T017、020、022、030、042、056、064 |
| FR-028 | T018、032、045、058、060～064、066 |
| FR-029 | T020～030、059 |
| FR-030 | T009、012、048、055、056、059 |
| FR-031 | T034、038、039、041、042 |

| SC | 任务 |
| --- | --- |
| SC-001 | T009～018、048、054～056 |
| SC-002 | T033～045 |
| SC-003 | T019～032 |
| SC-004 | T021、022、030、047、051、056 |
| SC-005 | T031、033～045、057 |
| SC-006 | T059、064、068 |
| SC-007 | T018、032、045、058、060～068 |

原批准68项任务统计：准备3，共用基础5，US1 10，US3 14，US2 13，US4 13，收尾10，总计68；实施按本轮证据逐项勾选。已批准本表及课程偏差，再进入implement；只读analyze可在确认前执行；本表生成本身不算完成实施任务。

## 独立复审追加修复（T069～T072，保留原68项编号与验证历史）

- [x] T069 在ReadOnlySessionCatalogTest先复现同秒不同小数精度的updatedAt分页排序RED，修复为完整Instant时间顺序/id稳定次序，核验只读零写。
- [x] T070 在scripts/tests/test_verify_cli_migration.py先复现--output覆盖既有文件/软链接RED，报告改为独占新建、不覆盖已有库/个人文件；记录GREEN。
- [x] T071 在CliCommandTreeTest先复现帮助含无效选项/必填显示错误RED，使各叶命令模型与实际允许/必填参数一致，不弱化别名与重复拒绝，打包帮助复验。
- [x] T072 修复RuntimeDatabaseLock.acquireQuery filename空值静态告警，保持固定拒绝与所有权边界；保留Linux首次quality失败，重跑Maven/双平台质量/进程/安全及独立复核。

## 收尾覆盖补强（保留原编号）

- [x] T073 补充生产Spring/Memory重启恢复测试：1006条规范消息、完整Tool交换、503条模型审计/1条工具审计与关联，重启前后全表逐列投影相等，1000条读取窗口不截断存档，零外呼；在最终门禁及独立复审中核验。

- [x] T074 保留最终macOS首次quality的EI_EXPOSE_REP2失败，将ChatCommand持有具体ReplChannel改为受限读/写行为引用，复用已有REPL安全/故障回归，重跑静态质量并独立核验，不添加规则豁免。

- [x] T075 保留第二次macOS quality暴露的ProfileDefinitionService四个父路径空值告警，统一显式检查父路径后固定拒绝；复用Profile创建/删除/链接零副作用测试，最终质量及独立复核确认，无规则豁免。

收尾结论：原T001～T068与追加T069～T075均有实现或明确记录证据；其中T064完成的是授权/NOT_RUN记录，不代表真实天气通过。工程门禁及独立复核完成，真实课程验收与延期能力单列；未commit/push。

## 用户授权的真实天气验收（保留此前NOT_RUN历史）

- [x] T076 执行第18节Linux临时新库真实CLI天气验收：生产交互命令处理两条消息并`/quit`，上海Open-Meteo、DeepSeek deepseek-flash、thinking disabled、重试0；外呼前强制最多6次模型发送/2次天气GET，核验天气事实与建议、工具结果进入模型、同会话三元组绑定、审计关联及关闭重开，保留首次结果，不自动重跑。本轮执行退出0，独立只读核验PASS；首次凭据缺失、核验脚本错误及后续修正记录保留，未重跑真实外呼；详见validation末尾。

# 第18节 CLI：代码变更、重点 Review 与验证

本节在 US2 归档 `edf49bf` 上补齐 CLI，工作分支为 `codex/006-cli-course-alignment`。按已批准的原68项任务实施，独立复审修复与覆盖补强追加 T069～T075，真实CLI天气验收追加T076，保留原编号和失败历史。当前改动尚未提交或推送；最终执行状态以 [006 验证记录](../../specs/006-cli-course-alignment/validation.md) 为准。

## 改了什么，按什么顺序阅读

原先 CLI 只有 init 和 stdin 到 EOF 的单次 chat。本轮增加12个叶命令、有界逐行 REPL、三元组持久会话、Profile 管理和严格只读摘要；原单次 JSON 保持兼容。第18节不是长期 Memory 的项目 US3，未扩充九模块。

| 阅读顺序 | 源码入口 | 主要变化 |
| --- | --- | --- |
| 1. 命令分流 | [AegisOsApplication](../../aegisos-boot/src/main/java/com/aegisos/boot/AegisOsApplication.java)、[AegisOsCli](../../aegisos-cli/src/main/java/com/aegisos/cli/AegisOsCli.java)、[CliArguments](../../aegisos-cli/src/main/java/com/aegisos/cli/CliArguments.java) | Picocli 4.7.7 命令树；帮助/版本先分流，非法输入固定安全错误；逐叶允许/必填参数模型 |
| 2. 能力装配 | [DefaultCliServices](../../aegisos-boot/src/main/java/com/aegisos/boot/cli/DefaultCliServices.java)、[CliRuntimeLauncher](../../aegisos-boot/src/main/java/com/aegisos/boot/cli/CliRuntimeLauncher.java) | 轻元数据路径与 chat/serve 运行时分开；关闭与停止释放资源 |
| 3. 交互 | [ChatCommand](../../aegisos-cli/src/main/java/com/aegisos/cli/ChatCommand.java)、[ReplChannel](../../aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/ReplChannel.java) | TTY 默认交互、管道默认 batch；严格 UTF-8、字节上限、/quit、EOF、失败终止；继续调用统一 AgentService |
| 4. 身份与持久化 | [SessionIdentity](../../aegisos-core/src/main/java/com/aegisos/core/memory/SessionIdentity.java)、[DefaultMemoryService](../../aegisos-memory/src/main/java/com/aegisos/memory/DefaultMemoryService.java)、[JpaSessionStore](../../aegisos-storage/src/main/java/com/aegisos/storage/session/JpaSessionStore.java) | `(channel,user,profile)` 幂等取得 UUID；候选会话与绑定同事务，唯一冲突回滚后最多一次重读 |
| 5. 新库与迁移 | [V4 schema](../../aegisos-storage/src/main/resources/db/schema/sqlite/V004__cli_session_bindings.sql)、[V3→V4](../../aegisos-storage/src/main/resources/db/migration/sqlite/V004__cli_session_bindings.sql)、[AuditSchemaInitializer](../../aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditSchemaInitializer.java) | 新库 V4；非空旧库明确要求离线迁移；原 V1～V3 SQL 保留 |
| 6. 严格只读 | [ReadOnlySessionCatalog](../../aegisos-storage/src/main/java/com/aegisos/storage/session/ReadOnlySessionCatalog.java)、[RuntimeDatabaseLock](../../aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeDatabaseLock.java)、[ExistingSchemaValidator](../../aegisos-storage/src/main/java/com/aegisos/storage/audit/ExistingSchemaValidator.java) | 不初始化/恢复，不建库或锁；既有 sidecar 独占短锁、只读连接、安全摘要和精确 Instant 分页 |
| 7. 配置管理 | [WorkspaceMetadataService](../../aegisos-boot/src/main/java/com/aegisos/boot/cli/WorkspaceMetadataService.java)、[ProfileDefinitionService](../../aegisos-boot/src/main/java/com/aegisos/boot/cli/ProfileDefinitionService.java) | 复用安全 YAML 与目录技能绑定；不解析凭据/建模型客户端；新建不覆盖，删除原子移到回收目录 |
| 8. Web 边界 | [ServeApplicationConfiguration](../../aegisos-boot/src/main/java/com/aegisos/boot/config/ServeApplicationConfiguration.java) | serve 仅显式装配现有 Web 基础和存储，监听本机；gateway 明确未启用、退出6 |

交互链路为 `CLI → AgentService → MemoryService → SessionStore / 既有 ReAct`。Provider 显式路由、有限重试、前置审计、Sandbox 和 Spring AI 自动工具执行禁用均复用 US2，不在 CLI 再造引擎。配置仍是独立 YAML、启动快照；正文逐轮安全重读。

## 重点 Review

| 风险与位置 | 必须成立的不变量 | 触发反例 / 零副作用 | 回归入口 |
| --- | --- | --- | --- |
| 轻重路径与安全输出 | help/配置元数据不装配模型或写库；不原样回显参数/YAML/异常 | 无凭据 help、坏 Profile、未知参数；模型发送=0，无新库 | CliLightPathTest、CliMetadataTest、CliDispatchTest、JAR strace 探针 |
| 身份、并发和原子提交 | 每个三元组单绑定单 UUID；不同维隔离；失败回滚候选 | 并发首次取得、绑定插入故障、已知密钥身份；无孤儿/零外呼 | SessionBindingTest、MemoryServiceTest、CliSessionPersistenceTest |
| REPL 生命周期 | 串行两消息同会话；quit/EOF不增加处理；失败不重发 | 首条quit、坏UTF-8、超限、输出故障、审计故障；审计失败时模型=0 | ReplChannelTest、CliReplWiringTest、verify-cli-runtime.py |
| 完整历史与恢复 | 读取窗口不截断存档；重启不重放旧外呼 | 1006消息含完整Tool交换，503模型审计/1工具审计；全表逐列前后相等，发送=0 | CliSessionPersistenceTest、CliSchemaMigrationTest、verify-cli-migration.py |
| 查询与锁所有权 | 不初始化/恢复/创建sidecar；所有退出释放自己的锁 | 缺库/缺锁/忙/硬链接/WAL拒绝；目录字节及mtime不变 | ReadOnlySessionCatalogTest、RuntimeDatabaseLockTest、打包进程探针 |
| 分页时间精度 | 按真实秒/纳秒倒序，id并列升序 | 同秒不同小数位；最旧记录不能排在最新之前；不写库 | paginationOrdersRealInstantAcrossVariableFractionalPrecision |
| Profile 删除 | 只移动已验证的定义来源，不跟随链接/级联历史 | 已有名称、链接目标、未知Provider/model；共享技能/历史不变 | ProfileDefinitionTest |
| 帮助与实现一致 | 只展示该命令接受的选项，必填/互斥真实可见 | delete 不出现 batch/port；重复、跨命令参数拒绝；help不要求workspace | CliCommandTreeTest、ChatModeTest、JAR帮助 |
| 迁移工具自身的输出 | 报告只独占新建；不覆盖任何既有路径 | 普通文件/既有SQLite/软链接报告目标拒绝，原内容不变 | scripts/tests/test_verify_cli_migration.py |
| serve/gateway 边界 | serve本机Web基础，停止释放锁；gateway无运行时 | 健康检查、查询锁忙、停止后chat重开；gateway退出6且不建库 | CliCapabilityTest、verify-cli-runtime.py |

六类边界核查：审计与处理链由生产装配/数据库重开验证；凭据仅受控注入；输入/输出/身份和元数据均做敏感保护；REPL串行且Spring/锁生命周期显式关闭；CLI没有另启Spring AI自动执行工具；YAML复用唯一加载器和显式Provider/model校验。默认测试仅临时库与回环服务，不证明真实厂商或天气事实。

独立 Deep 复审按11维度完成初审，发现3项：报告可覆盖旧文件、帮助参数失真、时间文本排序错误。修复追加任务，不将其改称误报。初始候选、置信度及最终关闭依据见验证记录；本地完整审查产物位于忽略目录 `.reviewing-code-diffs/`。工程构建结果与独立静态复审分别记录。

## 如何验证

仓库根目录、Java21、Maven3.9+。同一工作区 Maven/clean 串行执行，先保存报告再运行下一次 clean。真实模型 profile 默认关闭。测试及质量检查在依赖已缓存时可通过 `MAVEN_ARGS=-o` 使用离线 Maven；依赖漏洞插件要求在线模式，执行 dependencies 前应取消该变量中的 `-o`。

```bash
mvn -B test
mvn -B clean package
bash scripts/check.sh quality
bash scripts/check.sh secrets
# NVD凭据由受控环境注入，不填入参数或YAML。
bash scripts/check.sh dependencies
python3 -m unittest discover -s scripts/tests -p test_verify_cli_migration.py -v
python3 scripts/verify-cli-migration.py --output /tmp/cli18-new-migration-report.json
```

quality 实际执行 `clean verify -Pstatic-analysis`，覆盖格式、测试、打包及 SpotBugs；依赖扫描包含新增 Picocli，数据源错误也阻断。迁移报告路径必须尚不存在。迁移脚本仅生成临时 V3/V4 库、一致性备份和故障数据，不接受个人数据库参数。

Linux 完整验收（Python3、strace、OpenSSL、JDK keytool、PTY）：

```bash
CLI_BUILT_AFTER="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mvn -B clean verify -Pstatic-analysis -Daegisos.test.require-secure-directory-stream=true
python3 scripts/verify-cli-package.py \
  --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar \
  --built-after "$CLI_BUILT_AFTER"
```

package 脚本验证新鲜 JAR、12叶help、轻路径系统调用和元数据/init，再串行调用 runtime 脚本验证 PTY/pipe、重开、CRUD/查询零写、serve启停及gateway。回环 Provider 使用临时 CA 的 HTTPS；只影响测试子进程信任。macOS 可增加 `--allow-no-strace`，系统调用网络证明记 NOT_RUN；这是006历史阶段的SDS限制；007已改为平台受控实现，后续运行以[三平台指南](cross-platform-runtime.md)与[007验证](../../specs/007-cross-platform-runtime/validation.md)为准，原006证据不变。

具体手动使用、模式和退出码见 [CLI使用指南](cli-usage.md)，课程页码→FR/SC→场景见 [追溯表](../../specs/006-cli-course-alignment/course-traceability.md)。

## 结果与交付边界

本轮macOS与Linux全量质量各373项测试全部通过、无跳过；新JAR/临时迁移、密钥与当前依赖安全门禁通过（104项依赖、含Picocli、漏洞记录0）。依赖数据源曾失败，采用官方API经TLS及Git blob SHA校验刷新RetireJS缓存后重验通过，原FAIL保留。最终命令退出码及独立复核，以 [validation.md](../../specs/006-cli-course-alignment/validation.md) 的最新收尾条目为准，不复用旧 XML 或旧 US2 天气 PASS。首次格式阻断、HTTPS替身拒绝、SpotBugs失败及后续修复均保留。

本节按批准差异保留 V3 分表，不增加 `sessions.messages_json`。serve不提供Agent业务API，gateway不提供多通道；完整三模式对话延期。第18节真实CLI天气已通过本轮独立授权验收：3次模型发送、1次天气GET、重试0，两个处理均COMPLETED，天气事实/建议、同会话绑定、quit零额外外呼及关闭重开均通过。执行入口使用生产CLI命令与渠道、Provider、Sandbox和存储，只在调用端口加前置预算与观察；不是另一次真实打包JAR进程运行，进程层PTY/pipe引用本轮回环探针。独立只读核验通过，首次核验脚本误用PRAGMA版本字段已保留并更正，未重跑真实请求。个人库迁移及生产部署尚未执行，完整三模式仍延期，不能宣称全部课程能力通过。Git交付仍按用户单独指令进行。

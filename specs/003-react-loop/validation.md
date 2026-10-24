# US2 实施验证记录

## 初始状态（2026-10-22）

- Git：main，HEAD `75fb02684fd19dfa139b71fbb3f01e396cc76317`；规格及 checklist 已有未提交修改，保留原状。
- 当前任务：`specs/003-react-loop/tasks.md`，135 项；requirements 清单 16/16，通过仅表示规格质量。
- Java：Zulu 21.0.9；Maven：3.9.14。Boot 4.1.1、Spring AI 2.0.1 不变。
- 九模块：core、provider、memory、tool、channel-cli、web、storage、cli、boot；core 仅 JDK 与测试依赖。
- 保留 `specs/002-provider-abstraction` 和 `specs/005-provider-course-alignment` 的原任务及验证记录，本轮结果单独登记。
- `.gitignore` 已实测排除各模块 target；个人 `.aegisos`、数据库与凭据文件也被忽略。未发现本功能需要的新忽略规则。
- 不修改个人库；所有测试使用临时目录/回环服务/合成凭据。真实模型与天气请求默认关闭。

## 准备阶段

T001：完成初始核查。T002：补齐测试依赖及 tool 的 BOM 管理 HTTP/JSON 依赖。T003/T004：隔离夹具已建立。构建结果待运行，不预填 PASS。

T005：`mvn -q spotless:apply && mvn -B test package` 退出码 0。Surefire 31 个套件，{'tests': 144, 'failures': 0, 'errors': 0, 'skipped': 0}；九模块全部 SUCCESS。原始日志 `.tools/us2-baseline.log`（忽略入库）。默认无外部模型/天气请求。

## 基础阶段：公共契约、安全、V3 与来源

- T006～T011：新增 core 的 Agent/Memory/Tool/来源/审计关联契约，保留旧 Provider 与五/六参数审计构造器。四参 Provider 在链路接通前明确拒绝，不丢弃关联。
- T012～T017：合成秘密/敏感载体、正常说明/环境引用、规范 JSON 转义/中文 UTF-8、必需预算与 YAML/策略边界测试。`.tools/us2-security-red.log` 和 `us2-config-red.log` 已确认因缺少目标行为失败；对应 GREEN 后通过。
- T018～T025：V3 全量 schema、仅有效 V2 离线迁移、原审计全列保持、迁移中途故障回滚/重复拒绝、错归属 FK、严格 schema 校验和运行锁。V001/V002 原 SQL 不改；旧 V1→V2 测试保留，当前 V2 启动改为要求下一步显式迁移。锁在数据源前取得、close 后释放，包含独立进程及别名测试；每次借出连接验证外键。
- T026～T028：实际 Provider 凭据解析结果固定于启动快照；来源索引与有效 Profile 同步过滤，逻辑 name 与目录名不同仍找到原目录。
- 初始 RED：安全检查原无拒绝行为、配置及读取外壳未实现、空库仍是 V2、锁未互斥、来源无索引；均在可编译测试中确认，未把编译失败当行为证据。
- 其余基础回归：`mvn -B '-Dtest=*Test,*Tests,*TestCase,Test*,!MinimalContextTest' -Dsurefire.failIfNoSpecifiedTests=false test package`，退出码0，41套件/182测试，0失败/错误/跳过。明确排除了平台相关正文测试，不能作为T031完整通过。
- 诊断中一次仅排除测试名的 Surefire 选择器额外匹配旧 `ProviderSmokeIT`，其前置条件门禁返回 NOT_RUN、无外呼；随后改为显式 *Test 选择器，未启用真实模型验收。

### 平台决策与当前门禁

- 实测 `Files.newDirectoryStream` 在本机 macOS/Zulu21.0.9 为 `sun.nio.fs.UnixDirectoryStream`，不支持 SecureDirectoryStream。初次完整基础运行因此2个正文测试失败，T031未放行。
- 用户确认保留原契约：缺少安全能力的平台明确拒绝，正文正向验收必须在实际支持能力的Linux环境执行。补充于 `contracts/context.md`，没有弱化生产实现。
- macOS `mvn -B test package` 退出码0，正文测试走明确的平台拒绝断言；不把它计作Linux读取成功。Linux正向验收待运行，T029/T030/T031尚未标记完成。
- Linux环境：启动本机现有OrbStack。Docker Hub超时；Ubuntu临时机器初始化软件源校验失败；改用可达的微软官方JDK21镜像，并只复制Maven发行库/源码/隔离测试，不复制个人配置或凭据。

### T029～T031 最终结果：PASS

- 按用户确认，生产仍要求 SecureDirectoryStream。macOS 的测试只断言缺少能力时拒绝；Linux 强制 `-Daegisos.test.require-secure-directory-stream=true`，无跳过。
- 使用本机已有 OrbStack 建立专用 `aegisos-us2-tests` Ubuntu24.04/aarch64 测试机，官方HTTPS软件源、签名校验保留，安装 OpenJDK21.0.12.1；Maven3.9.14使用发行版库及无凭据设置，离线复用既有依赖缓存。未复制个人配置/真实密钥，未迁移个人库。
- Linux正向读取测试发现 AGENT.md 的批准根曾过宽（同工作区别的Agent目录可被链接读取），`.tools/us2-foundation-linux-red.log` 中反例实际失败；修正为当前Agent真实目录后通过。
- 补充敏感载体尖括号/OPENSSH与JSON Unicode转义反例，分别确认RED后修复；普通占位符/环境引用继续允许，原始字符串与JSON解码字符串都检查。
- macOS：`mvn -B test package`，退出码0；Linux：`mvn -o -B ... -Daegisos.test.require-secure-directory-stream=true test package`，退出码0。各43套件/191测试，failure/error/skip均0。Linux另逐份XML核对能力属性为true，MinimalContextTest与MinimalPromptTest被真实发现。
- Linux日志 `.tools/us2-foundation-linux-final.log`，macOS日志 `.tools/us2-foundation-macos-final.log`。早前Linux因未复制工程工作流文件导致旧隔离测试失败，补齐该受测输入后全通过；未删改隔离断言。
- T001～T031完成，基础门禁解除。后续故事及真实天气冒烟仍未验收。

## Phase 3：纯文本会话 MVP（T032～T048）

- RED：`.tools/us2-mvp-core-red.log`、`us2-mvp-storage-red.log`、`us2-mvp-boot-red.log` 固定实现前失败；生产装配额外由 `.tools/us2-wiring-behavior-red.log` 实际发现缺失 MemoryService Bean。
- GREEN：`.tools/us2-mvp-macos.log` 与 `.tools/us2-mvp-linux.log`，两次 `test package` 均退出0，九模块成功；本轮实际发现50个套件、207项测试，0 failures/errors/skips。主机旧 ProviderSmokeIT XML 并非本轮发现，未计入本轮结果；没有执行真实模型。Linux XML 全部确认强制 SecureDirectoryStream 属性为 true。
- A01/A02：生产 AgentRuntimeConfiguration 显式装配、临时 SQLite 连续会话与关闭重开、未知会话零外呼。无预算的旧 Web 基础启动兼容。
- A07：屏障保持首个模型请求在途，竞争请求立即 SESSION_BUSY 且不追加消息，首请求完成后锁与 ProfileContext 释放；数据库 RUNNING 唯一约束阻止另一入口重复开始。原 RuntimeDatabaseLockTest 保留跨进程验证。
- A11：真实 SQLite trigger 分别拒绝 USER、ASSISTANT、COMPLETED 写入；无假完成、无安全回复回显；已发生 Provider 成功审计保留。合成敏感输入零写零外呼，敏感回复不入会话。
- A02/A07/A11 的 macOS 正向正文路径按用户批准的平台契约明确返回 CONTEXT_FILE_UNAVAILABLE，0外呼；完整正向在 Linux 验收，没有放宽读取或 skip。
- ProviderRuntimeRegressionTest 与原002/005全部套件继续通过；显式处理关联先提交PREPARED与link，旧入口保持无关联；有限重试与SDK自动工具执行禁用由旧套件回归。
- 本阶段没有工具执行、CLI、恢复中断或天气验收完成声明。

## Phase 4：授权工具与观察闭环（T049～T072）

- RED：`.tools/us2-tool-policy-behavior-red.log`（6项行为失败）、`us2-http-behavior-red.log`、`us2-executor-red.log`、`us2-tool-audit-red.log`、`us2-sandbox-properties-red.log`、`us2-projection-red.log`；均实际发现目标测试、因缺失实现失败。早期格式检查失败不当作行为RED。
- 真实集成额外发现 SQLite/JPA BOOLEAN 类型适配缺陷，`.tools/us2-tool-loop-diagnose.log` 为正常闭环 PERSISTENCE_UNCONFIRMED；修复后 `us2-tool-loop-green.log` 的4个Linux闭环/故障用例通过。
- 有界读取反例 `.tools/us2-bounded-read-red.log` 证明客户端默认在流关闭时drain剩余正文，2秒等待失败；改为响应上限+1后主动取消/关闭连接，再关闭响应；超限错误不被关闭异常覆盖。
- GREEN：`.tools/us2-tools-macos-final.log`、`.tools/us2-tools-linux-final.log`，两个完整 `test package` 退出0，九模块成功；各61套件/229项测试，0失败/错误/跳过，Linux强制SDS属性。
- A03/A04/A05：真实协议mapper+SQLite+两个隔离回环服务；两轮模型、两个按序GET与闭合观察；同一原ID跨响应复用只1GET/3模型调用，wire ID配对稳定，原持久身份不改。整批重复ID零GET。
- A10：默认拒绝/精确scheme-host-port-path-query/混合DNS拒绝、私网和保留地址分类；固定单地址DNS，3xx/4xx/503不重发；超限/媒体类型/UTF-8/压缩拒绝；DNS超时不得后续连接，十次超时归还执行许可；超大响应不等待剩余正文。
- A11：begin提交失败0GET；complete或观察保存失败仅1GET且无后续模型；合成秘密结果终止、不进入会话，工具审计保留RESPONDED事实。物理SQL列/约束、事务可见性、重复完成拒绝均验证。
- 本阶段全部网络是测试明确注入的回环许可；生产仅http_get、targets默认空，没有私网YAML开关，没有真实模型或天气请求。

## Phase 5：停止、故障与完整追踪（T073～T086）

- RED：`.tools/us2-provider-limit-red.log` 实际接受1MiB以上成功响应（缺预期异常）；`.tools/us2-recovery-red.log` 发现重开仍为RUNNING。均先于对应实现。
- GREEN：`.tools/us2-stopping-macos-final.log`、`.tools/us2-stopping-linux-final.log` 两个平台完整test/package均退出0；各69套件/242测试，0失败/错误/跳过。
- A06：N=1/2/10模型逻辑响应与accepted count均恰N；末轮OPEN组持久、最后工具0、总GET=N-1且无N+1；纯文本末轮可完成。核心替身矩阵与真实mapper/SQLite计数相互验证。
- A02/A11：RuntimeRecoveryTest与PipelineCrashIntegrationTest覆盖模型begin后、响应后、工具执行后审计前、审计后观察前；重开RUNNING/未结束turn→INTERRUPTED，安全记录保留，旧审计STARTED不猜终态，0自动重放。恢复故障阻止启动。
- A07：ProfileContext正常/异常/嵌套/线程隔离与线程中断清理；事件经Memory持久化，固定安全STOPPED/TOOL_CONFLICT/TOOL_REUSED/RECOVERED元数据，不保存正文。
- ProviderFailureLoopTest使用显式IPv4隔离服务与500ms读取超时，配置2次重试：同requestId、attemptNo=1..3、0 accepted response、实际HTTP发送3，Agent无外层重试。503按现行不可重试契约保持；早期测试错误地将503视为可重试，已修订测试而非修改生产策略。
- ProviderResponseLimitTest覆盖成功/错误响应、有/无Content-Length，超1MiB为不可重试协议错误、RESPONDED，零隐藏重发；原Provider超时/重试/成功测试继续通过。

## Phase 6：最新正文与受控历史（T087～T105）

- RED：`.tools/us2-context-red.log`，实际发现ContextLoaderTest/PromptBuilderTest，显式Bootstrap/Skill读取失败且完整历史超限未裁剪。`.tools/us2-trim-events-red.log` 进一步发现可选记忆移除后缺少持久事件（期望1，实际0），其余敏感集成用例通过。
- GREEN：`.tools/us2-context-macos-final.log` 与 `.tools/us2-context-linux-final.log`，两次完整test/package退出0；各80套件/266项测试，0失败/错误/跳过；Linux逐XML检查强制SDS属性。
- A08/A09：角色→AGENT→Bootstrap声明顺序/默认顺序→绑定名称排序Skill→时区时间；正文逐轮重读、字面环境引用不展开、Skill替换拒绝、显式缺失/非法UTF-8/I/O故障拒绝、仅真实默认缺失WARN。ProfileSourceMappingTest与RuntimeSnapshotTest验证名称≠目录、重名索引清除、Profile改动重启生效而正文下一请求生效。
- A10：ContextHistoryQueryTest真实SQLite25个完成处理仅取最近20、顺序稳定、失败处理排除，投影不删除52条原消息。PromptBuilderTest验证先移除可选记忆再完整历史裁剪；ContextProjectionIntegrationTest使用原mapper验证跨处理重复原ID，2处理/4模型/2GET，持久原ID不改。中文文件/用户/规范JSON容量超限零外呼。
- A11：AGENT/Bootstrap/Skill合成秘密零模型、不入会话；转义工具参数在执行前拒绝；可选长期记忆敏感内容必须先拒绝，安全超限片段才移除。已发生的Provider/工具元数据仍保留。
- WARN/裁剪/复用/停止等全部经Memory持久事件；新增固定裁剪原因区分可选记忆、历史处理数、当前工具组数、规范字节变化。事件提交失败停止且不发送模型，不退化为日志。
- 平台边界沿用用户决定：macOS明确拒绝读取；Linux运行所有正向断言。文档见 `docs/engineering/us2-runtime.md`，未交付CLI或真实天气结论。


## Phase 7：CLI 与默认关闭的天气验收程序（T106～T125）

- RED：`.tools/us2-cli-red.log` 与 `.tools/us2-weather-red.log` 确认新增测试因缺少 CLI/天气判据实现而失败；`.tools/us2-verifier-full-red.log` 确认新验证器尚不存在。后续修复格式/实现，未删除行为断言。
- macOS 九模块 `mvn -o -B test package`：`.tools/us2-cli-macos-gate.log`，退出 0；最终本轮 XML 为 **86 suites / 285 tests / 0 failures / 0 errors / 0 skipped**。
- Linux 独立副本 `python3 .tools/us2-linux/gate.py test package`：`.tools/us2-cli-linux-gate.log`，退出 0；同样 **86 suites / 285 tests / 0/0/0**，强制 SecureDirectoryStream 正向路径。
- Python 验证器：`.tools/us2-verifier-offline.log`，8 项离线测试通过；包含缺配置/XML/零发现/skip、失败/错误、错 runId/指纹/时间、超额度、天气布尔 false、伪造 PASS 和真实 V3 临时库审计不一致。
- 本轮 Linux JAR 探针：`.tools/us2-cli-package-probe.log`，**PASS:CURRENT_JAR_INIT_NO_OVERWRITE_NO_DATABASE_NO_IP_NETWORK**。strace 证明两次 init 无 IP connect/send/bind/listen；创建模板、不建库、不加载 Spring 运行时/凭据解析器，二次拒绝覆盖。
- CLI 只实现 init / 单次 stdin chat / 显式 session 续聊；固定七字段 JSON，0/2/3/4/5 退出码；显式非 Web source，关闭 context 释放数据源/运行锁；全局 YAML 唯一来自批准 workspace，不从 cwd 回退。
- 独立 `real-react-smoke` 默认关闭，两个用户处理、每处理≤3轮、全程≤6模型物理发送/≤2GET、重试0；扣额度位于外部动作之前。天气判据为坐标/单位/时效/十进制舍入/最新实际发送观察及非空建议，不能只靠非空文本。
- 默认真实 DeepSeek/天气请求 **0**。A12 与 A13 的故事5离线范围 PASS；**A14 / T133 仍为 NOT_RUN**，没有套用历史 Provider 冒烟授权。尚未完成最终质量/秘密/依赖门禁，当前不能宣称 US2 完整验收。


## Phase 8：文档、架构与迁移复核（T126～T128）

- README/AGENT/us2-runtime 同步当前 CLI、V3、仅 http_get 与默认长期空边界；macOS 拒绝、Linux SDS 正向契约保持。
- 架构逐项结果见 architecture-review.md。补充保留网段测试先 RED（`.tools/us2-address-review-red.log`）再修复分类；新增恢复事务提交被拒绝的真实 SQLite 回滚断言。
- `.tools/us2-review-focused.log`：SandboxPolicyTest / RuntimeRecoveryTest / CliCommandTest / ReactSmokeEvidenceTest 聚焦通过，退出0；未使用额外trigger导致的schema拒绝冒充提交失败。
- 临时V2演练 `.tools/us2-migration-rehearsal.log`：backup API一致性备份→V3增量→全列双向EXCEPT→重开完整性/外键→停机恢复V2备份全部PASS；脚本不接受个人库路径。操作边界和数据损失说明见 docs/engineering/us2-migration.md。

## 最终工程门禁（T129～T132，2026-10-22）

### T129：测试、打包与静态质量 PASS

| 环境 / 检查 | 本轮证据（本地忽略目录） | 结果 |
| --- | --- | --- |
| macOS `mvn -B test` | `.tools/us2-final-serial-test.log` | 退出0，九模块成功 |
| macOS `mvn -B clean package` | `.tools/us2-final-serial-package.log` | 退出0，九模块成功 |
| macOS `bash scripts/check.sh quality` | `.tools/us2-final-serial-quality.log` | 退出0，九模块成功；SpotBugs 0 bugs/errors |
| Linux 当前源码 `clean verify -Pstatic-analysis` | `.tools/us2-final-linux-current.log` | 退出0，九模块成功；SpotBugs 0 bugs/errors |
| Linux 本轮新 JAR + strace init 探针 | `.tools/us2-final-cli-package-probe.log` | 退出0，PASS:CURRENT_JAR_INIT_NO_OVERWRITE_NO_DATABASE_NO_IP_NETWORK |
| Python 证据验证器反例 | `.tools/us2-final-python.log` | 退出0，8项通过 |
| 临时 V2 备份/迁移/比较/重开/恢复 | `.tools/us2-final-migration.log` | 退出0，PASS |

- 最终 Java 发现 **86套件 / 288测试 / 0失败 / 0错误 / 0跳过**。Linux 对每份本轮 XML 核对 `aegisos.test.require-secure-directory-stream=true`，正向正文读取没有被 macOS 拒绝路径替代。macOS 仍只对不支持平台验证明确拒绝。
- 初次质量检查依次发现 core 2项、storage 5项、CLI和boot的内部表示/空引用问题，日志 `.tools/us2-quality-first.log`、`us2-quality-second.log`、`us2-quality-third.log`、`us2-quality-fourth.log`；全部修复。`.tools/us2-quality-diagnostic.log` 是跳测试/继续构建的诊断运行，不能当作门禁通过证据；最终采用上表完整运行。
- `.tools/us2-final-package.log` 曾在主机/Linux并行负载时出现 ProviderRegistryTest 与 ProviderResponseLimitTest 的正向超时。修复正向夹具预算后，串行三门全部通过；原故障/有限重试断言没有放宽。
- 最后仅调整敏感测试注解的合成私钥头部常量拼接（运行时输入相同）。`.tools/us2-secret-carrier-focused.log` 重新编译并通过 SensitiveContentGuardTest；上表最新 Linux 完整质量运行亦包含该改动。
- 架构复核另以固定时钟反例修正持久历史排序，并为授权列表与上下文补充不可变快照；详见 [架构复核](architecture-review.md)。

### T130：历史与工作区秘密扫描 PASS

- Gitleaks 8.30.1，`bash scripts/check.sh secrets` 本轮退出0：`.tools/us2-final-secrets-rerun.log`。历史扫描8个提交和工作区扫描均为 **0发现**，扫描规则未降低。
- 首轮 `.tools/us2-final-secrets.log` 的历史为0，工作区为2；均为 SensitiveContentGuardTest 及本地 Linux 源码副本中的合成私钥头部文本，没有真实私钥。将源码常量拆开拼接后保留完全相同的运行时反例输入，通过重新编译/测试/重扫；未新增豁免规则。

### T131：依赖安全扫描 PASS

- 2026-10-22 18:04:54（北京时间）完成 `bash scripts/check.sh dependencies`。本轮证据目录 `.tools/us1-evidence/authenticated-20261022-175742/`，以其中 `command.json` 的扫描子进程 **exitCode=0** 为准，不以辅助Python进程退出码替代。
- Dependency-Check 13.0.0 聚合分析 **103个依赖 / 0个报告漏洞**，九模块package及分析成功；本轮新增的HttpClient/Jackson随全工程一起分析。报告为该目录内 `dependency-check-report.json/html`，原始日志 `dependencies.log`；CVSS门槛7、failOnError、数据更新均保留，无新增抑制规则。
- NVD更新出现多次重试，最终成功；报告记录 `NVD API Last Checked=2026-10-22T18:04:23+08`、`Last Modified=2026-10-22T09:17:17Z`，没有沿用历史PASS。NVD/RetireJS/KEV分析完成。OSS Index因缺少其独立凭据被扫描器默认禁用，这是覆盖范围限制，不宣称全数据源覆盖；本轮未修改该行为。
- 既有Keychain→扫描子进程环境注入，秘密未进入命令参数、报告或Git。第一次本地辅助脚本因零个tracked target文件漏建快照目录而退出，外部扫描未启动；修复本地目录创建后执行本轮扫描。辅助脚本自身不传播child退出码，已单独核验command.json及日志。

### T132：真实验收前置证据汇总 PASS

- T127～T131全部通过，当前没有未修复的已知架构/质量/秘密/依赖问题；历史失败及修复过程保留于本记录。Python验证器8项、迁移演练及Linux强制能力正向测试均已通过。
- A01～A13离线/临时库/隔离网络范围、A15人工架构复核通过。PDF17-01～10→FR/SC→场景→实际测试证据见 [课程追溯第5节](course-traceability.md)，FR-001～038和SC-001～011逐项映射见 [验收契约](contracts/acceptance.md)。关联到A14的标准仍保留真实部分未验收。

### T132完成时的状态快照（真实执行前）

- 真实 DeepSeek 模型请求与真实天气 GET 均为 **0**。默认测试的禁用 harness 结果为 NOT_RUN，不能代替 A14。
- **T001～T132完成（132/135）**；T133为NOT_RUN。T134/T135已准备当前追溯和一致性说明，但依赖T133的最终真实收敛仍未完成，保持未勾选。故事5的离线部分通过，整个US2尚未完整验收，不放行US3/US4。
- `git diff --check`通过，当前仍在main；未自动commit/push。保留原规格/checklist修改，实施期间未改清单marker，002/005任务与验证历史未改；各模块target和本地`.tools`仍被忽略。未迁移或读取个人会话库，未恢复Provider Web测试接口。
- `.specify/extensions.yml`不存在，无需分派after_implement hooks。
- 文档收敛及真实验收配置准备后的秘密复验 `.tools/us2-final-secrets-closure.log` 退出0：Git历史/当前工作区均0发现；`git diff --check`再次通过。
- 已准备一份不含凭据的本地忽略YAML `.tools/us2-linux/react-smoke.yml`，对应Linux `/tmp/aegisos-us2-react-smoke.yml`；仅创建临时父目录和校验配置，没有启动harness或进行目标预探测。固定DeepSeek/Open-Meteo目标、上海坐标、同会话两消息、≤6模型/≤2GET、零重试。当前进程 `DEEPSEEK_API_KEY` 为空，专用 `io.aegisos.deepseek-api-key` Keychain条目未找到；已向操作者请求本次显式启用及受控注入位置，不接收正文凭据。

## T133：一次已授权真实天气验收 FAIL

### 授权、环境与证据

- 操作者明确授权一次，要求环境变量注入。凭据经关闭回显的输入→进程内存→管道传到Linux，仅注入Maven验收子进程的 `DEEPSEEK_API_KEY`，未存入文件/命令参数/Keychain；本记录不复述正文。真实执行结束后子进程退出，未自动重跑或换模型/换天气源。
- Linux临时新库，runId=`react-20261022-180730-34b9bce8`；非秘密配置指纹=`aefe802ad475311052373a54c5a2e05bbceb20f44a2e7904ae7630a10581a5f6`。固定DeepSeek deepseek-flash、上海31.23/121.47及Open-Meteo批准目标，预算与T133一致。
- 执行前UTC锚 `2026-10-22T10:11:15.036138+00:00`；命令结束 `2026-10-22T10:12:24.168423+00:00`。本轮 `clean verify -Preal-react-smoke` 的 **Maven退出1**，随后独立Python验证器 **退出1 / FAIL**，没有因失败用例跳过/零发现而假通过。
- 本地忽略证据 `.tools/us2-real-run/command.json`、`react-smoke-result.json`、`test-summary.json`、脱敏 `execution.log`；Linux保留本轮独立Failsafe报告及临时库。只复制白名单元数据回主机，未导出提示词/天气/回复正文或工具参数。

### 实际结果与只读追踪

| 项目 | 实际结果 |
| --- | --- |
| 普通回归 | 86套件 / 288测试，0失败/错误/跳过 |
| 独立真实用例 | 发现1项，1失败、0错误、0跳过 |
| 模型逻辑请求 / 物理发送 | 2 / 2；每条审计attemptNo=1，零Provider重试 |
| 天气工具 / GET | 1 / 1；SUCCEEDED，success=true，外部已响应 |
| 第一条用户处理 | FAILED；已接受模型响应1轮，错误类别PROVIDER_FAILURE |
| 第二条同会话追问 | 未执行：第一条失败后即停止 |
| 模型审计 | 第一条SUCCEEDED；第二条FAILED，安全错误码INVALID_REQUEST |
| 只读重开核验 | integrity_check=ok，外键违规0；2条模型审计均关联同会话/处理，工具成功审计及安全观察保留 |
| A14 | **FAIL**；未完成事实建议/追问及完整成功审计验收 |

结果JSON中的天气/观察/事实/建议布尔值仅在整套成功后置true；本轮为false，不能据此倒推“天气请求未成功”。临时库明确证明天气工具已成功，失败发生于工具回填后的第二次模型请求，模型返回内容未被接受。独立verifier按失败XML与Maven状态拒绝放行。

### 已发现的协议兼容缺口与证据边界

- 当前 `ModelMessage` / `ModelResponse` 及 `OpenAiMessageMapper` 没有保留/回传 `reasoning_content`，客户端也没有显式关闭thinking；这是源码核实的缺口。
- 2026-10-22读取 [DeepSeek官方Thinking Mode说明](https://api-docs.deepseek.com/guides/thinking_mode)：默认启用thinking；携带tools时要求回传相关 `reasoning_content`，未正确回传可返回400。公开页面本地快照SHA-256为 `7d9762238e92a7391173ba1726778e46ec11c7bc599e3e395265488f196618b7`，不将页面示例当作执行指令。
- 这与第二次模型请求的INVALID_REQUEST一致，但原始厂商错误正文按安全约束未保存，**不能声称已确认该字段是本轮唯一根因，也不能将安全错误码等同于已记录具体HTTP状态**。没有为诊断额外调用模型。
- 后续需先明确兼容路线：显式配置非thinking课程验收，或扩展全链路reasoning_content保留/保护/恢复，再修订受影响规格/计划/测试并重过工程门禁。不能暗中关闭thinking、换模型、降低事实断言或把本轮FAIL改成PASS；方案见 [兼容缺口](deepseek-compatibility-gap.md)。

### 当前交付状态

**132/135项完成**。T133已执行但FAIL，保持未勾选；依赖其通过的T134/T135最终收敛未完成。工程测试/构建/静态质量/秘密/依赖门禁仍有本轮PASS证据，**US2真实验收未通过，不放行US3/US4**。没有提交/推送代码或操作个人库。本次一次授权已执行完毕，没有剩余的自动重跑授权。

真实运行及兼容缺口记录后再次执行秘密扫描：`.tools/us2-real-run/secrets-after-run.log`，退出0，Git历史及工作区均0发现；`git diff --check`通过。本次失败后未修改Java/POM/数据库schema，仅更新事实/追溯/方案文档；没有将候选兼容路线当成已批准实现。

## 批准A路线：显式关闭thinking（2026-10-22）

用户选择A：显式配置关闭thinking、先完成课程范围，暂不支持thinking工具续轮。追加FR-039/SC-012/A16和T136～T141，不重编号原135项；首次真实FAIL及原证据保持不变。本增量没有新的真实模型或天气外呼。

### 实现与RED/GREEN

- ProviderProperties/ProviderDefinition固定可选模式，缺省UNSPECIFIED不传扩展，只有精确disabled为DISABLED；非法标量在凭据解析前注册为不可用。严格CLI YAML另拒绝显式null/对象/列表；普通Spring属性绑定null是未声明，不冒称能区别显式YAML null。
- OpenAiCompatibleAdapter保存快照，生产OpenAiMessageMapper每轮显式生成thinking.type=disabled。旧构造器/缺省请求继续兼容，不改core消息或V3schema、不存reasoning_content，无名称特判或SDK自动工具。
- 专用Java/Python配置必须disabled并参与指纹；harness把声明传入同一生产配置/mapper，不在测试程序直接补HTTP字段。init模板只增加说明注释。
- 正式行为RED：`.tools/us2-thinking-provider-behavior-red.log`发现7项、5项因缺wire扩展/非法配置未拒绝而失败，0 errors。早期夹具JSON转义错误的日志不作为行为RED。`.tools/us2-thinking-config-red.log`与`us2-thinking-verifier-red.log`记录旧严格解析器不接受新合法配置、缺失模式未拒绝等反例。
- GREEN：`.tools/us2-thinking-focused-green.log`，ProviderThinkingModeTest 7项、ReactSmokeEvidenceTest 6项、CliRuntimeWiringTest 4项，共17项零失败/错误/跳过；真实loopback wire核对首轮与工具续轮扩展、模型/temperature/tools/调用ID配对、启动快照、缺省不按名称推断，以及非法模式零凭据解析/HTTP。Python验证器9项PASS，日志`.tools/us2-thinking-verifier-green.log`，未访问真实模型/天气。

### 新版工程门禁

| 门禁 | 结果与证据 |
| --- | --- |
| macOS Maven test / clean package / quality | 三命令exitCode均0；`.tools/us2-thinking-gates.json`及对应full-test/full-package/full-quality日志。完整XML为87套件/297测试，failure/error/skip均0 |
| Linux clean verify -Pstatic-analysis | exitCode=0，87套件/297测试，failure/error/skip均0；逐份XML的require-secure-directory-stream=true。日志`.tools/us2-thinking-linux-quality.log`，汇总`.tools/us2-thinking-test-summary.json`；macOS拒绝与Linux正向契约不变 |
| 新JAR init探针 | exitCode=0，PASS:CURRENT_JAR_INIT_NO_OVERWRITE_NO_DATABASE_NO_IP_NETWORK；执行前UTC锚2026-10-22T10:43:39.514483+00:00，日志`.tools/us2-thinking-linux-jar-probe.log`。临时目录两次init，未运行chat |
| 秘密扫描 | Gitleaks8.30.1，历史/工作区各0发现，exitCode=0；`.tools/us2-thinking-secrets.log`。凭据未写入代码/配置/报告 |
| 依赖漏洞扫描 | child exitCode=0，103依赖/0报告漏洞；`.tools/us1-evidence/authenticated-20261022-184516/command.json`及report。NVD处于上次成功更新后240分钟有效期内，按既有策略复用，未关闭更新或降低CVSS7门槛。RetireJS/KEV分析完成；OSS Index因缺独立凭据未覆盖，此限制与既有门禁一致 |

NVD凭据仅Keychain→扫描子进程环境，没有使用--store或暴露凭据。辅助脚本自身返回不代表扫描成功，本记录读取了command.json中真实child exitCode=0。原002/005任务和validation无差异，target/.tools继续被ignore；未提交、推送或迁移个人库。

### 新的可审查验收配置（仅准备，NOT_RUN）

- 首次输入备份`.tools/us2-real-run/config-used.yml`，run-id仍为react-20261022-180730-34b9bce8、未含thinking-mode；原FAIL的JSON/XML/指纹保持原样。
- 新输入`.tools/us2-linux/react-smoke.yml`及Linux `/tmp/aegisos-us2-react-smoke.yml`：run-id `react-20261022-184544-25261963`，thinking-mode=disabled；非秘密指纹 `3f241a8fdff7762fd47f4b82d540250b66f7194ff1cd98d893b3a0919315bcf9`。Linux调用load_config/fingerprint离线校验PASS，记录`.tools/us2-thinking-prepared-config.json`，没有运行真实harness。
- 仍用DeepSeek deepseek-flash与上海31.23/121.47 Open-Meteo，同会话两处理，每处理≤3轮、总≤6模型发送/≤2天气GET、Provider重试0，失败不自动重跑。新配置没有凭据值；执行需新的明确授权和环境注入。

### A路线真实执行前结论（保留当时状态）

**138/141项完成**：T001～T132、T136～T141已完成。T133首次FAIL保留，新版A14为NOT_RUN；T133～T135保持未勾选，US2整体尚未放行，不推进US3/US4。已检查.specify/extensions.yml不存在，无after_implement hooks。规格/计划/架构/课程/README与运行指南已同步批准A路线及实际边界。

## 新版真实天气验收：PASS（T133，2026-10-22）

本次用户明确授权一次新的验收，沿用此前指定的环境变量注入方式。使用已审查的A路线配置，未换模型、天气源、消息、预算或事实判据；失败后的首次记录仍完整保留。

- **run-id**：react-20261022-184544-25261963；非秘密配置指纹 `3f241a8fdff7762fd47f4b82d540250b66f7194ff1cd98d893b3a0919315bcf9`，thinking-mode=disabled。
- **执行前UTC锚**：2026-10-22T10:49:54.748893+00:00；独立IT开始2026-10-22T10:50:32.389336060Z，完成2026-10-22T10:50:39.876723508Z；Maven/verifier结束2026-10-22T10:50:40.223448+00:00。
- **环境与入口**：Linux aegisos-us2-tests，Java21；clean verify -Preal-react-smoke，强制require-secure-directory-stream=true，临时新V3库。DEEPSEEK_API_KEY仅经隐藏输入与管道注入Maven子进程环境，未写文件/命令参数；未使用个人配置或迁移个人库。
- **Maven=0，verifier=0**，输出PASS:WEATHER_FACTS_AND_AUDIT_VERIFIED。普通测试87套件/297项，独立ReactWeatherSmokeIT恰1套件/1测试，均failure/error/skip=0，全部XML确认强制SDS属性。
- **实际次数**：逻辑模型请求3、物理发送3、天气GET/工具执行1，Provider重试0；未超过6模型/2GET总预算，每处理≤3轮。只执行本次一次，无隐藏重跑或预探测。
- **会话与处理**：唯一session `ce4acfd9-616e-4295-ad5f-0c703c85ec13`；处理 `889a8e67-6fc5-4f8a-9e91-ff5bb96c733d` 和 `cd6b7740-8e2c-4b13-a987-fc65868e8536` 均COMPLETED，接受轮次分别2和1，最终状态可靠提交。
- **事实链**：模型主动调用http_get查询批准的上海Open-Meteo；weatherValid/observationForwarded/factMatched/advicePresent全部true。坐标/单位/时间/十进制舍入/唯一气温与非空建议按契约3.1验收；两次最终答复均核对其实际发送Prompt中的最新有效观察。第2处理未新增GET，使用同会话已保存的观察完成追问。此处不导出天气/回复正文，也不把“建议非空”的机器断言夸大为独立人工质量评分。
- **独立只读重开**：auditReopened/associationValid=true；另以SQLite mode=ro/query_only重开，integrity_check=ok、foreign_key_check=0；1会话、2完成处理、3条SUCCEEDED/attemptNo=1/success=true模型审计与3条llm_request_links逐轮匹配，1条SUCCEEDED/http_get/RESPONDED工具审计。消息角色计数USER=2、ASSISTANT=3、TOOL=1，不导出正文。
- **独立证据目录**：`.tools/us2-real-run-disabled-react-20261022-184544-25261963/`，含排他单次标记attempt-started.json、config-used.yml、command.json、react-smoke-result.json、test-summary.json、独立failsafe-reports、audit-readback.json与execution.log。该目录被忽略，不提交临时库/正文或凭据。
- **首次FAIL保护**：`.tools/us2-real-run/`既有6个证据文件逐一SHA256校验不变；新版证据存独立目录，未覆盖原run-id/指纹/FAIL。首次错误仍不能断言唯一根因；本次PASS只证明显式disabled课程范围，不支持thinking工具续轮。

## 最终追溯与工作区复核（T134/T135）

- PDF17-01～10→FR-001～039/SC-001～012→A01～A16→实际任务/测试及本验证记录的对应关系已在course-traceability.md、contracts/acceptance.md和tasks.md收敛。原FR/SC保留，批准A路线作为项目协议增量单独追溯；A14从新版NOT_RUN更新为真实PASS，首次FAIL历史不删除。
- T133、T134、T135已按实际证据完成，总计**141/141项**。spec/plan/tasks/quickstart、README/AGENT、架构与运行指南均同步当前状态和边界；没有尚未批准的工程变更或待真实复验项。
- 基线Java21、九模块、Boot4.1.1/SpringAI2.0.1、显式路由、有限重试、前置持久审计、Memory门面与唯一Sandbox不变。正文读取Linux/SDS强契约不变，macOS明确拒绝；长期空能力、仅http_get、不支持thinking工具续轮的范围不变。
- 工程/安全结果沿用本次A路线同一代码的完整门禁；随后真实命令再次发现297项默认测试和1项真实IT全部通过。本次真实完成后仅整理文档与本地证据，没有再修改Java或构建配置。秘密扫描另在本次导出证据与最终文档完成后执行，历史/工作区均0发现，见新版证据目录secrets-after-run.log。
- 收尾秘密扫描曾将首轮FAIL校验清单中“含secrets的文件名作为JSON键+SHA256值”误判为generic-api-key；已改为明确path/sha256字段结构，6个原始摘要仍逐一匹配，未修改扫描规则。最终扫描exitCode=0，历史/工作区各0发现。
- 最终diff空白/文档链接/141项唯一编号/39FR与12SC/16项只读checklist均核对通过；target与.tools继续被ignore，002/005原任务与validation无差异，未恢复Provider Web测试接口。
- .specify/extensions.yml不存在，无after_implement hooks。工作区仍为main且保留未提交修改；未commit/push、迁移个人库、生产部署、再次调用真实模型或自动进入US3/US4。

**最终结论：US2本次批准范围的实现与验收完成。** 仅对上列平台、模式和功能边界成立；下一阶段须另行明确范围。


## 代码评审修复增量（T142～T150，2026-10-22）

本节追加于原141项完成记录之后，原任务和两次真实验收历史不变。用户授权修复三项评审问题、先补回归再实现及Maven验证/复审，暂不commit/push。本次不执行真实模型/天气调用、不迁移个人库、不改写002/005记录。

修复前摘要、原评审副本及源码基线保存在本地忽略目录 `.tools/us2-review-fixes/`；后续复审以该基线区分原US2实现与此次修复。16项规格质量清单全部已勾选，无.specify/extensions.yml扩展钩子。

### 敏感JSON字段名：RED → GREEN（T143/T144）

- 先新增转义api_key/password/token/secret字段名的明文值、嵌套/数组、数字/布尔值及重复字段反例，同时保留安全引用/null/占位符/普通字段与数组正例。目标是保留标准JSON字段关联，不扩大到任意编码或字符串内嵌JSON递归。
- RED：core聚焦29项中6项断言失败（应抛SENSITIVE_CONTENT而未抛），0错误/跳过；Linux强制SDS的SensitiveOutputBoundaryTest共2项，新增入库边界反例实际执行到模型响应，返回MAX_ITERATIONS而非SENSITIVE_CONTENT，1项失败/0错误/跳过。日志security-red.log、security-linux-red.log。Linux初次命令曾被格式检查阻断，格式修正后才确认行为RED，不把格式失败当缺陷证据。
- GREEN：JsonStringValues保留相邻的解码字段/标量关系，同时继续检查每个解码字符串，正式JSON形状校验仍由业务解析器承担。core29项及Linux的core29项+boot2项全部通过，0失败/错误/跳过；数据库只保存安全USER，无ASSISTANT工具意图或tool_invocations。日志security-green.log、security-linux-green.log；对应Maven命令退出0。


### 数据库生命周期锁：RED → GREEN（T145/T146）

- RED：macOS与Linux真实JDBC/连接池/独立JVM测试均为5项中2项失败，0错误/跳过；已有硬链接库未拒绝，强制更换物理连接后硬链接探针退出0（成功拿锁），预期5（安全拒绝）。确认原review的POSIX机制在Java/JDBC生产接线中实际成立，不再仅依赖Python类比。日志storage-red.log、storage-linux-red.log。
- 实现中补核同一机制：同JVM的第二次抢锁即便抛OverlappingFileLockException，关闭新开的同sidecar描述符仍会释放首个持有者的OS锁。先补跨进程断言并见RED：独立JVM退出0而预期3（占用）。增加打开channel前的JVM规范路径认领，失败候选不能关闭既有持有者的描述符；日志storage-overlap-red.log、storage-overlap-green.log。
- GREEN：生命周期只锁独立sidecar；数据库与锁文件须为单链接普通文件，可验证unix:nlink，不支持此属性时固定AUDIT_UNAVAILABLE拒绝。数据库0600创建、不截断已有内容；软链接/规范路径继续同锁，多硬链接直接拒绝。连接池保持正常轮换，不以关闭轮换绕过问题；macOS/Linux各5项全部通过，0失败/错误/跳过。
- 连接替换测试确认旧SQLiteConnection已关闭、新物理对象不同；同规范路径仍退出3、后建硬链接别名退出5；原STARTED审计不变，删除测试别名并关闭持有者后可重开。该测试使用临时库，无真实模型/个人库。
- 迁移脚本与当前迁移说明改为单sidecar POSIX锁和nlink检查，SQLite连接关闭不再影响锁。临时V2备份/升级/双向旧行比较/重开/恢复演练退出0，PASS:TEMPORARY_V2_BACKUP_V3_BIDIRECTIONAL_COMPARE_REOPEN_AND_RESTORE，日志migration.log。

### Profile注册前校验：RED（T147）

- 通过真实ProfileLoader与完整生产装配分别输入max_iterations=101、max_history_turns=1001；保留缺省、1/1、100/1000有效边界及正常Profile隔离正例。
- RED：12项中4项断言失败、0错误/跳过；非法配置仍出现在Profile/来源索引，错误不会在启动加载时被发现。日志profile-red.log。


### Profile注册前校验：GREEN（T148）

- ProfileLoader在mapper返回后、发布Profile与AgentSource之前调用ProfileRuntimePolicy；非法上界映射为固定INVALID_FIELD诊断，正常Profile不受影响。失败时只保留参与重名冲突判定的安全名称，不保留非空无效候选。
- macOS聚焦ProfileLoaderTest/ProfileSourceMappingTest/ProfileRuntimeRegistrationTest共13项全部通过，0失败/错误/跳过；非法Profile没有来源索引，createSession/process均UNKNOWN_PROFILE，sessions/agent_processings/session_messages均零行，零模型请求。日志profile-green.log，Maven退出0。该校验在正文读取前，不依赖macOS/Linux的SDS差异。


### 复审补充边界：再次 RED → GREEN（T143～T146）

以下是在三项修复复审中发现并继续收敛的同类问题，不删除首轮记录，也不将旧门禁冒充最后代码的结果。

- **完整敏感标量**：首轮把解码键值重新拼为文本，仍会把 `null hunter2`、`env:REFERENCE actual-value`、`${TOKEN} actual-value` 等首词误当占位，或漏检引号开头的值。先补测试：core37项中6项失败、0错误/跳过（second-review-red.log）；Linux强制SDS的模型接受边界6项中4项失败，实际MAX_ITERATIONS而非SENSITIVE_CONTENT（second-security-linux-behavior-red.log）。随后增加完整解码字段/标量检查，精确匹配安全占位与完整环境引用，保留空值正例。最终聚焦core37项、Linux入库边界6项全部通过，零工具执行，数据库只含安全USER；不递归解释字符串内嵌JSON。
- **锁文件角色冲突**：数据库规范目标若是另一库的 `.lock`，可能被SQLite打开/关闭。先补直接路径/软链接两项回归，锁测试5项中2项失败、0错误/跳过（second-lock-behavior-red.log）；随后在任何sidecar打开前拒绝规范目标的大小写无关 `.lock` 后缀，保留原锁和无`.lock.lock`副作用断言。前期格式检查失败的second-lock-red.log、second-security-linux-red.log不作为行为RED证据。
- **硬链接sidecar的拒绝也须保锁**：若打开后才检查nlink，再拒绝并关闭描述符，仍会释放同进程另一规范路径持有的锁。再补回归，6项中1项失败、0错误/跳过：删除测试别名后独立JVM拿锁成功退出0，预期占用3（sidecar-hardlink-red.log）。随后在打开已有sidecar前检查普通文件/nlink=1，打开后继续复核；最终macOS锁6项+生命周期2项通过（sidecar-final-green.log）。不承诺抵御同OS权限下恶意并发替换文件。
- **Linux串行聚焦**：second-linux-focused-green.log退出0，core37+ProviderService3+storage7+boot6共53项、零失败/错误/跳过；该轮早于最后一项sidecar硬链接补修，不能代替最后全工程Linux门禁。首次完整Linux运行中已有ProviderServiceTest正向夹具120ms超时产生1个error，linux-quality.log和gates-round1副本保留；串行聚焦3项通过，未改生产或测试超时策略，最后代码仍需完整Linux重新验证。

### 中断后的验证续跑（2026-10-24）

2026-10-22最后一轮全工程命令因会话中断未完成；maven-test-interrupted.log单独保留，旧319项完整门禁保存到gates-round1。2026-10-24核对修复前摘要后继续验证，源代码只含本次预期修复，依赖/POM与002/005历史未改。最后门禁结果另记于下，不把中断/旧汇总判为通过。


### 最后质量门禁的静态反馈

2026-10-24首轮续跑test/clean package均退出0（88套件/334测试，零失败/错误/跳过）；quality在storage阶段由SpotBugs阻断：规范路径的getFileName返回值缺少显式非空检查，1 bug/0 errors。该次quality未执行后续模块，汇总中旧target的XML不算本次完整发现数。失败日志与汇总完整保留于 `.tools/us2-review-fixes/gates-static-failure/`。随后增加显式filename非空门槛（仍映射固定AUDIT_UNAVAILABLE，不抑制规则），对最后代码重新运行test、package、quality与Linux门禁，以下最终结果以新命令实际退出码/XML为准。


### 最后代码的工程验证（T149，2026-10-24）

所有命令串行运行，未启用real-provider-smoke/real-react-smoke，测试仅使用替身、回环服务和临时库。最后代码相对原297项默认测试新增37项；默认构建不把平台拒绝伪装成正文成功，Linux另外强制SDS验证。

| 验证 | 实际结果 | 本地证据（.tools/us2-review-fixes/） |
| --- | --- | --- |
| macOS `mvn -B -o test` | exitCode=0，88套件/334测试，failure/error/skip均0 | maven-test.log、maven-gates.json |
| macOS `mvn -B -o clean package` | exitCode=0，88套件/334测试，failure/error/skip均0，九模块打包成功 | maven-package.log、maven-gates.json |
| macOS `bash scripts/check.sh quality` | exitCode=0，88套件/334测试，failure/error/skip均0；9模块SpotBugs各0 bugs/0 errors | maven-quality.log、maven-gates.json |
| Linux `clean verify -Pstatic-analysis` | exitCode=0，88套件/334测试，failure/error/skip均0；每份XML的require-secure-directory-stream=true；9模块SpotBugs各0 bugs/0 errors | linux-quality-final.log、linux-gate-summary.json |
| 临时库迁移/恢复演练 | exitCode=0；V2备份→V3→双向旧行比较→重开→V2恢复全部PASS | migration-final.log |
| Python冒烟证据验证器反例回归 | 9项全部通过，不运行真实冒烟 | verifier-tests-final.log |
| 秘密扫描 | Gitleaks8.30.1，命令exitCode=0，历史/工作目录各0发现 | secrets-final.log及根目录脱敏JSON报告 |

秘密扫描首轮8项命中均为SHA256清单的JSON键内文件名含secret等字样；逐项确认值仅为64位十六进制摘要。将两份清单重排为独立path/sha256字段，所有映射逐项保持，未改扫描规则或加忽略项。secrets-initial.log/初次脱敏发现与manifest-reformat.json保留该过程；不是删除真实秘密以外的历史记录。原评审报告/源码、原任务与验证正文不受此格式调整影响。

保护核验：原003 tasks/validation完整字节前缀保留，原T001～T141勾选/验收历史不变；150个任务编号唯一。002/005任务与validation、九个模块POM共13文件与修复前摘要相同，根POM无Git差异；无新增依赖，因此本修复未重新运行NVD扫描，不将旧依赖门禁表述为本次扫描结果。target/.tools仍忽略，未迁移个人库、真实模型/天气外呼或生产部署，未commit/push。


### 独立复审与完成判定（T150，2026-10-24）

- 使用reviewing-code后端Deep流程，敏感、Profile、数据库由独立审查者分组只读复核，主执行者核对文档，覆盖21/21文件；另由独立评分审查者读取最终diff和必要上下文，最终新增/待修复问题0、过滤0、既存问题0。原三项与修复中发现的边界逐项核实闭合，未仅依赖实现者自评或把历史候选隐藏为误报。
- 原评审完整报告和首轮候选分别保存在 `.tools/us2-review-fixes/original-review/` 与 `review-round1/`；最终本地报告 `.reviewing-code-diffs/_report.md`、独立评分 `_result_scored.json`、完整性 `_completeness.json` 可复核。本节作为仓内长期记录，不依赖把临时数据库/正文/密钥提交到Git。
- T142～T150共9项全部完成，原141项加增量后**150/150项**；requirements质量清单仍16/16，仅核对未修改。根POM/002/005及原003历史保护核验通过，文档当前边界与最终结果已同步。
- 本轮最后代码macOS/Linux各334项通过，Maven打包/静态门禁通过，迁移演练、Python9项与秘密扫描通过。首次Linux夹具超时、静态非空反馈和清单摘要误报均保留，最终通过是修正/核验后的新命令结果，不覆盖旧失败。
- 没有.specify/extensions.yml，不存在after_implement钩子。未commit/push，未真实模型/天气外呼、个人库迁移或生产部署；原真实FAIL/PASS仍只代表原run-id和批准模式，本次不增加新的真实验收结论。

**结论：本次三项review修复及追加任务完成。** 后续功能范围和Git交付另按用户指令处理。

## US2 收尾交付复核（2026-10-24，aegisos-lesson-dev）

本次用户显式调用 `$aegisos-lesson-dev US2 收尾`，按收尾模式复用 [变更、Review 与验证指南](../../docs/engineering/us2-changes-review-verification.md)。基线 `main@75fb02684fd19dfa139b71fbb3f01e396cc76317` 加原未提交 US2 实现；本次只更新指南并追加本节，不改 Java、POM、任务及既有验证正文。

### 证据复核与当前结果

| 检查 | 命令/方法 | 本次结果 | 证据位置 |
| --- | --- | --- | --- |
| 任务与源码基线 | `python3 .tools/us2-closeout/verify.py`；比对285份修复前清单与21份最后复审清单 | exitCode=0；150/150任务完成且编号唯一；修复前差异均在已记录范围，最后源码摘要匹配，后续文档改动单列 | `.tools/us2-closeout/checks.json` |
| 已有工程与复审记录 | 同上，读取最终 Maven/Linux JSON、review completeness/scored | 原 macOS三组及Linux一组均88套件/334测试，failure/error/skip=0；Linux全部强制SDS；独立复审21/21、未关闭问题0 | `.tools/us2-review-fixes/maven-gates.json`、`linux-gate-summary.json`；`.reviewing-code-diffs/_completeness.json`、`_result_scored.json` |
| 引用与补丁检查 | 同上，检查指南/导航/验证记录的本地文件链接及 `git diff --check` | exitCode=0；受检文件均存在、无空白错误；不将链接存在性当作行为测试 | `.tools/us2-closeout/checks.json` |
| 秘密扫描 | `bash scripts/check.sh secrets` | exitCode=0；历史/工作目录各0发现，PASS | `.tools/us2-closeout/secrets.log`及根目录脱敏JSON报告 |

本轮没有运行 Maven，因此“本轮 Java 测试发现/失败/错误/跳过数”均不适用，**不能填334作为本次发现数**。334是T149既有执行结果，经本轮证据复核引用。没有新增独立代码复审，T150的21/21与零问题也只作为既有复审证据，不声称本次完成新一轮全量 review。没有重新运行 NVD、真实模型/天气验收、个人库迁移或生产部署；未 commit/push。

收尾指南现已包含模块阅读地图、完整处理链路、按风险排列的 review 清单、三项缺陷的反例与零副作用断言、聚焦/全量/Linux命令及六项DoD。原T001～T150和真实验收FAIL/PASS记录保留，进入下一US及Git交付另按用户指令执行。

## US2 Git 归档交付（2026-10-24）

用户在收尾完成后明确授权新建 `codex/us2-react-loop`，提交 US2 代码、规格、验证记录与 `aegisos-lesson-dev`，并推送至 origin。此前“暂不 commit/push”以及各阶段“未提交”的记录保留为历史状态。

- 分支起点：`main@75fb02684fd19dfa139b71fbb3f01e396cc76317`；交付目标：`origin/codex/us2-react-loop`，远端为现有 GitHub 仓库。main 不在本次推送范围。
- 交付内容：US2 ReAct/持久会话/受控工具/CLI 与装配、三项 review 修复及回归、课程规格/任务/验证历史、阅读与运行/迁移指南、收尾 skill 和对应 CI 调整。
- 提交前核对：150/150任务、285份修复前清单、21份最终清单及既有macOS/Linux各334项测试记录匹配；skill结构校验通过。本次不重新运行Java/NVD或真实模型验收。
- 原 `.tools`、review临时证据、target、个人配置及数据库不纳入提交；提交前再次执行秘密扫描并检查暂存清单。本节随交付提交保存，提交哈希和远端引用的最终核对结果在本次交付回复中提供，不预先宣称推送已完成。

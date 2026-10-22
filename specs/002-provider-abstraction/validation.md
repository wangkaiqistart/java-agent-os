# US-1 实施验证记录

日期：2026-10-21。所有模型调用使用本机 HTTP stub，不含真实凭证。已有工作区修改保留，构建前已备份历史 tracked target 文件（47 个）。当前状态：**代码、测试及依赖安全门禁通过，T001～T051 全部完成**；23:23 的最终结果见本文末尾，此前失败记录原样保留。详细原始执行日志位于本机忽略目录 `.tools/us1-evidence/`，本文保留可复现命令和结论。

## 基础阶段 T001～T016

- Spring AI 2.0.1 发布源码确认：OpenAI 客户端采用 SDK 4.49.0；`maxRetries(0)` 关闭 SDK 重试。AI 2 的同步 ChatModel 已移除内部 Tool 执行分支，旧 `internalToolExecutionEnabled(false)` API 不再存在；本次不使用 ChatClient/Advisor，不提供执行型工具。
- 保留 Boot 4.1.1 / Framework 7.0.9 / Java 21 / 九模块。Hibernate core 与 community dialect 均为 7.4.5.Final；SQLite JDBC 3.53.4.0。
- T012 RED：`mvn -B -pl aegisos-storage -am -Dtest=JpaLlmCallAuditStoreTest -Dsurefire.failIfNoSpecifiedTests=false test` 实际发现 4 个测试，因未实现的初始化抛出 AUDIT_UNAVAILABLE 失败，非编译或下载失败。
- GREEN：`mvn -B -pl aegisos-core,aegisos-provider,aegisos-storage -am test` 退出 0，core 5、provider 1、storage 4，共 10 项全部通过。
- `mvn -B clean package` 退出 0，九模块 SUCCESS；含既有 boot 17 项，共 27 项测试，0 失败/错误/跳过。
- 引入 JPA starter 后 Boot 自动配置需要 DataSource，因此基础阶段提前显式导入独立 AuditStorageConfiguration；未提前接入 Provider 业务装配。默认数据库无自动建表更新，版本 SQL 初始化先于 JPA。

## 故事 1 T017～T027

- RED：Provider 测试实际发现 22 项，其中未实现服务导致 21 项失败，随后完成生产实现。
- `mvn -B -pl aegisos-provider,aegisos-storage -am test`、指定 `ProviderWiringTest`、`mvn -B test`、`mvn -B clean package` 均退出 0。总计 49 项（core 5/provider 22/storage 4/boot 18），无失败、错误或跳过。
- HTTP stub 实际收到次数：默认超时 1；maxRetries=2 耗尽 3；两次超时后成功 3；400/401/403/404/408/429/500/502/503/307 各 1；Tool-only 和混合响应各 1，未发起下一轮。
- 审计顺序为 begin→adapter→complete；boot 独立上下文使用真实 SQLite，成功/401 各一条终态。
- API 研究修正：Spring AI 默认 SpringAiOpenAiHttpClient 硬编码 retryOnConnectionFailure(true)，其 customizer 无法关闭。因此显式注入 SDK HttpClient 实现，OkHttp 设置 retryOnConnectionFailure(false)、followRedirects(false)、followSslRedirects(false)，SDK maxRetries=0，SDK 日志 OFF、ObservationRegistry.NOOP。
- Spring AI 的用量转换强制 int 且要求三个字段齐全。传输层在内存提取原生 nullable long 并从交给模型映射的响应中移除 usage；最终用量按原始字段返回，不补零、不推算。测试包含缺 usage、部分 usage、30 亿输入 Token。

## 故事 2 T028～T037

- RED：Registry 3 项测试失败；另行执行 boot 配置/快照测试 17 项，因未实现的注册表装配失败，未把上游模块阻断当作 boot 测试已运行。
- GREEN：Registry 3、配置 16、快照 1 共 20 项独立测试通过；`mvn -B test` 与 `mvn -B clean package` 退出 0，共 69 项（core 5/provider 25/storage 4/boot 35），九模块 SUCCESS。
- A/B 真实 HTTP stub 各收到 1 次指定请求；9 种缺失/未知/大小写/空白身份组合均零外呼、REJECTED；同名配置拒绝启动。
- 启动快照：旧端点收到 2 次、重启前新端点 0 次，关闭重建后新端点 1 次。配置文件、绑定对象、Provider/模型/端点/凭证引用与凭证值均改变后，旧实例仍使用原值；凭证只按启动次数解析。
- 单条缺密钥、未知协议、空模型、非法重试/时长/URI 保持不可用；默认根 URL https://api.openai.com、重试 0、连接 5s/读取 60s；HTTP 仅 test profile 的 loopback 可用，无启动探测。
- 重试配置仍为 int，逻辑尝试和审计序号使用 long，计算 N+1 不溢出。

## 故事 3 与最终回归 T038～T050

- RED：7 项故障汇总测试中，上游 `model_not_found` 分类测试失败；修正为 MODEL_UNAVAILABLE。另补充“格式合法的误传凭证标识”安全测试并观察失败，改为仅回显/审计已注册 Provider 及其允许模型，未知身份置空。这是安全元数据白名单的细化，已同步数据模型与接口契约。
- 六种请求级 externalOutcome 场景均通过：首次 begin 失败 NOT_SENT；完整 401 RESPONDED；超时后 401 UNKNOWN；超时后下一次 begin 失败 UNKNOWN；成功后 complete 失败 UNKNOWN；超时耗尽 UNKNOWN。
- 实际 SQLite 验证：外呼处理器以另一连接读取到已提交 STARTED；模型等待时另一写事务可提交；前置独占锁对应 0 外呼；响应后的独占锁导致 AUDIT_UNAVAILABLE/UNKNOWN、仅 1 次发送、数据库保留 STARTED。另有提交故障注入回滚、连接关闭、只读文件、非法目录、目录 0700 和重开持久化测试。
- HTTP 补充发现：OkHttp 4.12 即使 retryOnConnectionFailure(false)，对 `503 + Retry-After: 0` 仍会重发 POST，独立探针及 Provider 回归测试均复现 2 次发送。请求体改为 isOneShot=true 后，回归测试确认 1 次发送、1 条审计；未删除或弱化失败用例。
- 安全哨兵运行时随机生成，检查成功/拒绝/认证/协议/超时/审计异常、SQLite 字段与文件、捕获日志及公开异常，不输出哨兵值。源码不含运行时凭证；Tool schema/arguments、正文、URL 和 SDK cause 不进入审计。
- 最终测试总计 **91 项**：core 5、provider 37、storage 9、boot 40；失败/错误/跳过均为 0。每个测试命名为 *Test，由 Surefire 3.5.2 实际执行。
- `bash scripts/check.sh quality` 于北京时间 **22:58:08** 退出 0，九模块 SUCCESS、Spotless/SpotBugs 无错误或告警。曾发现的集合暴露识别、重复读取可空返回值和过宽 catch 均通过代码调整解决，没有关闭规则或添加抑制。
- 最终配置使用显式 EntityManagerFactory 和 JPA 仓库，不依赖兄弟包自动扫描；persist 只插入，complete 以 id + STARTED 条件更新。审计端口通过 TransactionTemplate 的 REQUIRES_NEW 短事务提交后才返回。

### 最终命令证据

| 命令 | 北京时间完成 | 退出码 | 结果 |
|---|---|---|---|
| `mvn -B -pl aegisos-provider,aegisos-storage -am test` | 22:58:16 | 0 | 51 项测试通过 |
| `mvn -B -pl aegisos-boot -am -Dtest=ProviderWiringTest -Dsurefire.failIfNoSpecifiedTests=false test` | 22:58:23 | 0 | boot 4 项实际运行 |
| `mvn -B test` | 22:58:38 | 0 | 91 项全部通过 |
| `mvn -B clean package` | 22:58:56 | 0 | 91 项，九模块及父 POM 全部 SUCCESS |
| `mvn -B -pl aegisos-core,aegisos-provider,aegisos-storage -am dependency:tree -Dscope=compile` | 22:58:59 | 0 | core 无框架运行依赖；provider 只有 core，无 storage/memory/tool/web；Hibernate core/dialect 同为 7.4.5.Final |
| `mvn -B -pl aegisos-storage help:effective-pom` | 22:59:00 | 0 | 版本管理与 JPA/SQLite 生效 |
| `bash scripts/check.sh secrets` | 23:08:21 | 0 | 历史 5 次提交与工作目录扫描，均 no leaks found |

原始日志与命令开始/结束时间、退出码位于 `.tools/us1-evidence/final-commands.jsonl`；早期失败执行也保留，不将其覆盖为成功。最终表采用最后一次成功执行。

最终展开产物哨兵检查：543 个文件、195554 个内容条目（含嵌套 JAR），运行时敏感哨兵发现为 0；结果见 `.tools/us1-evidence/canary-scan.json`。该检查与 Gitleaks 互补，不代表任意未知秘密均可被检测。

### 依赖安全修复与扫描环境

新增 AI 客户端引入 Kotlin 运行库，首次扫描对 2.3.21 与旧 stdlib-common 1.9.10 报告 CVE-2026-53914（CVSS 9.8，修复边界 2.4.20）。以 Kotlin BOM **2.4.21** 在 Boot BOM 前管理 JVM 库。该 BOM 对 common 使用 pom 类型，无法管理 Okio 3.6 引入的旧 jar 类型 metadata，因此进一步管理 Okio/Okio JVM **3.16.4**，其现代 JVM 依赖不再携带旧 common 归档。未添加漏洞抑制或提高门槛；最终组合的 91 项测试和静态分析已通过。

最终 `bash scripts/check.sh dependencies` 于北京时间 **23:06:19** 返回 **1（FAIL）**。插件报 `UpdateException: Error updating the NVD Data`，底层为 `NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key`；本机没有配置 NVD_API_KEY。匿名单条 curl 请求虽返回 200，不能证明插件增量更新成功，也不足以确定是凭证还是请求处理问题。最终 JSON 报告包含更新异常，101 个分析条目、0 个漏洞匹配；这是使用旧数据库的有限结果，**不能作为安全门禁通过**。报告记录 NVD 上次检查时间为当天北京时间 15:20。

曾尝试 NVD 官方 modified 数据源，下载按官方 SHA-256 校验通过；本地镜像因缺少年度元数据而返回 404，未成功更新，未交付该实验配置。最终仓库继续使用标准 API 更新与原有 nvd-auth Profile；没有关闭更新、放宽门槛或篡改缓存。SQLite JAR 携带原生 DLL，分析器需要 .NET 8，本机忽略目录 `.tools/dotnet` 中的 Microsoft 8.0.32 已按官方 SHA-512 校验，脚本可发现该路径；不属于 Java 应用运行依赖。

恢复步骤：通过环境或 CI Secrets 注入有效的 `NVD_API_KEY`，确认网络可用后重新执行 `bash scripts/check.sh dependencies`；若使用组织镜像，必须具备插件要求的完整数据及可信来源。成功后再记录实际报告并完成 T051。此前不放行项目 US-2。Sonatype OSS Index 因缺少独立凭据未覆盖。

### 交付产物与工作区保护

当前源码对应的新 JAR 保存在 `.tools/us1-evidence/artifacts/aegisos-boot-0.1.0-SNAPSHOT.jar`，SHA-256 和实际打包版本见同目录上层 `artifact-manifest.json`；包含 Okio 3.16.4，已不包含旧 kotlin-stdlib-common JAR。原始依赖 HTML/JSON 报告保存为 `dependency-check-final-report.*`。验证结束后，47 个 tracked target 文件按本轮开始前的字节快照恢复并校验；这些历史产物不代表当前源码，运行时使用保留的新 JAR 或重新构建。源码及任务开始前的其他修改未回滚。

## FR/SC 证据映射 T051

| 要求 | 实现与验证证据 |
|---|---|
| FR-001 | core ProviderService、DefaultProviderService；ProviderServiceTest、生产 ProviderWiringTest |
| FR-002 | ProviderRegistry/ProviderProperties 列表与重名校验；RegistryTest、ConfigurationTest |
| FR-003 | 按请求的精确名称与模型匹配；RegistryTest 的 A/B 与大小写/空白拒绝 |
| FR-004 | env 引用、可选根 URL、显式模型集合；ConfigurationTest 与真实 loopback 装配 |
| FR-005 | 两个同协议端点的真实请求计数各 1；RegistryTest |
| FR-006 | 安全错误枚举与分类；配置错误、模型下线、认证、限流、HTTP、协议和网络测试 |
| FR-006a | ProviderRetryTest：默认 1、配置 2 重试最多 3；只重试网络/超时；每次独立审计 |
| FR-006b | RegistryTest 的缺失/空模型，MODEL_REQUIRED、0 外呼 |
| FR-007 | ModelResponse 请求身份/requestId/文本/结构化调用/nullable long 用量；Mapper/ServiceTest |
| FR-008 | V001 llm_calls 与短事务；JpaLlmCallAuditStoreTest 的提交、唯一性、持久化与重开 |
| FR-009 | 校验拒绝 REJECTED/attempt_no=0；成功与失败各一次终态；审计不可写场景不承诺持久化 |
| FR-010 | env 凭证解析一次、受限 DTO 与审计列、秘密及哨兵扫描；ProviderSecurityTest |
| FR-011 | ProviderException/AuditUnavailableException 无原始 cause；错误/日志哨兵断言 |
| FR-012 | schema 描述无执行体，AI 2 不接 Advisor/ChatClient；Tool-only/混合响应只发 1 次 |
| FR-013 | core/provider/storage/boot 共 91 项测试，Surefire 实际执行 |
| FR-014 | ProviderSnapshotTest：配置文件/绑定对象/凭证来源改变，旧实例不变、重建后生效 |
| FR-015 | 完整 Maven test、clean package、quality；最终成功状态与边界见本记录 |
| FR-016 | 外呼前 begin 已提交；真实锁及后续 begin 故障阻止对应尝试；Wiring/AuditFailureTest |
| SC-001 | A/B 只访问指定端点，stub 各 1 次 |
| SC-002 | 未知/缺失/不可用身份零外呼；重名启动失败 |
| SC-003 | 仅限审计可写：接受/拒绝/重试各一行；有用量时保留，未知字段为 null |
| SC-004 | 动态秘密哨兵、数据库/日志/异常断言、Git/工作目录秘密扫描及展开产物扫描 |
| SC-005 | 生产最小上下文只装配配置、Provider、JPA/SQLite，无 ReAct/Memory/Tool/Web 业务 Bean |
| SC-006 | 默认零重试；N+1 上限、断连恢复、超时耗尽和无隐藏重发验证 |
| SC-007 | 重建前旧端点 2、新端点 0；重建后新端点 1；凭证按启动次数解析 |
| SC-008 | 缺模型明确拒绝，不使用 SDK 默认模型 |
| SC-009 | 仅限对应尝试前置失败：第一次 begin 失败 0 外呼；重试 begin 失败不增加外呼；不抹掉旧尝试 |

**未执行**：真实 DeepSeek/Kimi/OpenAI 调用为 NOT_RUN；未触发远端 CI、未部署、未做业务或生产验收。审计不提供外部幂等性，UNKNOWN 不能被当成未发生。


## T051 安全门禁重试（2026-10-21 23:16）

按用户要求重新执行 `bash scripts/check.sh dependencies`，北京时间 23:16:23 开始、23:16:53 结束，耗时 30.444 秒，退出码 **1（FAIL）**。构建阶段的 91 项测试仍全部通过；聚合扫描再次因 NVD 更新失败而阻断，底层错误仍为 `NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key`。当前进程未配置 `NVD_API_KEY`。

新报告为 101 个分析条目、0 个漏洞匹配，但保留更新异常，NVD 上次检查仍为当天北京时间 15:20；不能标记为通过。T051 保持未完成，US-2 暂不放行。恢复 NVD 更新所需的环境密钥与网络条件后，再执行相同命令；不在聊天、源码或命令参数中传递密钥。

本次原始日志、命令时间/退出码、HTML/JSON 报告及汇总保存在 `.tools/us1-evidence/retry-20261021-231623/`。47 个 tracked 构建文件按本次重试前的独立快照恢复，SHA-256 校验全部一致；没有回滚源码或修改安全门槛。


## T051 完成：有凭据的最终验证（2026-10-21 23:23）

用户提供 NVD 凭据并授权记住后，已将原文保存到本机 macOS 钥匙串；service 为 `io.aegisos.nvd-api-key`、account 为 `NVD_API_KEY`。长期记忆仅保留此引用；通过 Security API 读取后，向扫描子进程注入 `NVD_API_KEY`，没有将密钥写入源码、文档、命令参数或日志。钥匙串写入后已读取核对成功。

| 检查 | 实际结果 |
|---|---|
| 命令 | `bash scripts/check.sh dependencies`（凭据由子进程环境注入） |
| 开始 / 结束 | 北京时间 23:19:15 / 23:23:06；231.100 秒 |
| Maven 返回 | 0，BUILD SUCCESS；九模块及父 POM 成功 |
| NVD API 更新 | 成功；最终下载进度 2151/2151，API Last Checked 为 23:22:57，Last Modified 为 15:17:19Z |
| 依赖报告 | 101 个分析条目，0 个漏洞匹配，0 个更新/分析异常 |
| 测试 | core 5、provider 37、storage 9、boot 40，共 91 项，失败/错误/跳过均为 0 |
| 配置边界 | 保持标准 NVD API、自动更新、CVSS 7 阻断与 failOnError；未使用实验镜像或改写缓存时间 |

网络请求曾自动重试，最终增量更新成功。报告中的旧 NVD Cache Last Checked 属于独立 feed 缓存元数据；本轮以已成功更新的 NVD API 元数据及零分析异常判定 API 路径通过，未改动旧缓存字段。Sonatype OSS Index 因缺少独立凭据仍未覆盖，该覆盖限制保留；本结果不代表零未知风险。

原始日志、命令结果、完整 HTML/JSON 报告、测试汇总及构建文件恢复核验保存在 `.tools/us1-evidence/authenticated-20261021-231915/`。47 个 tracked 构建文件按扫描开始前的独立快照恢复，SHA-256 全部一致；源码及既有修改保留，新 Provider JAR 仍使用前文保留的产物。

T051 已勾选，FR/SC 对照及此前 quality/secrets/test/package 证据不变；51 项任务全部完成，项目 US-2 的 US-1 前置门禁已满足。真实模型厂商调用、远端 CI、部署和业务验收仍为 NOT_RUN。

最终凭据定向扫描检查 576 个文件、139142 个内容条目（含嵌套 JAR），密钥原文匹配为 0；报告为上述证据目录的 `credential-scan.json`。北京时间 23:25:06 重新运行 `bash scripts/check.sh secrets`，退出 0，Git 历史与工作目录均通过。钥匙串内容不在扫描输出中显示。

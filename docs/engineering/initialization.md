# 工程初始化执行报告

日期：2026-10-21。执行范围：review 并完善 `aegisos-init`，随后执行工程初始化。当前分支 `main`；本次未提交、推送或部署。

工程基础实现、测试与打包已完成。用户批准 Boot 4 / Framework 7 迁移后，本地依赖门禁已通过：本次有效扫描无漏洞匹配；完整结果见文末 Boot 4 增量。这不代表零未知漏洞或已具备业务 Agent 能力。

## 初始状态与处理

| 项目 | 执行前 | 本次结果 |
| --- | --- | --- |
| 工程 | 九模块、Java 21、Boot 3.3.5、占位类 | 九模块保留；初始化先用 Boot 3.5.10，安全修复经过 3.5.16，当前按用户授权迁移至 Boot 4.1.1 / Framework 7.0.9 |
| 日志 | 默认日志，无请求关联 | logstash JSON、受限请求 ID、MDC 清理和安全请求完成事件 |
| HTTP/API | 无基础错误和请求体边界 | ProblemDetail、校验、32 KiB JSON、16 KiB Header、连接/上传等待限制 |
| 监控 | 无 Actuator | 最小 health、可选本机独立 Prometheus；dev 才启用 OpenAPI |
| Java 质量 | 无测试和门禁 | 16 项行为测试、Spotless、SpotBugs、Maven 构建 |
| 安全 | 无可执行扫描入口 | 固定版本 Gitleaks、Dependency-Check、失败阻断脚本和 CI |
| 文档 | README/AGENT 仍称没有工程 | 更新实际状态、开发命令和独立基础工程 Spec |

本机实际使用 Zulu Java 21.0.9、Maven 3.9.14。未新增模块，core 没有引入 Web、Spring AI 或存储实现。没有实现 Provider/ReAct/Memory/Tool/Session/人工或电话业务。

## Skill 审查与修订

入口：[aegisos-init](../../.agents/skills/aegisos-init/SKILL.md)。

1. 原 Skill 缺少强制可执行的本地/CI 入口，易停在插件配置；现在提供 quality/secrets/dependencies 共享入口，缺工具或数据源失败必须非零退出。
2. 首次格式检查与旧骨架不匹配的处理不明确；现在允许授权范围内的少量骨架迁移，禁止借机重排业务代码或覆盖用户修改。
3. 旧安全插件版本可能无法消费当前数据；核对并固定 SpotBugs 4.10.5.0、Dependency-Check 13.0.0，设置有限超时/重试，禁止静默跳过。
4. 聚合扫描绑在父模块 verify 会先于子模块构建；改为先 package，再执行 aggregate。NVD key 非空时由独立 nvd-auth Profile 引用环境变量。
5. 补充真实装配、测试指标导出、测试 Controller 隔离、特定静态误报复核，以及已跟踪构建物的备份恢复要求。
6. 同步 Maven/Boot 片段、验证清单与基础约定；模板均为合并参考，不覆盖现有工程。`quick_validate.py` 检查通过。

执行中修正：测试环境指标导出需 `@AutoConfigureObservability`；ObjectMapper 复制配置以避免共享可变引用；请求 ID 严格白名单导致的单条 Header 污点误报以具体规则注解说明，并补 CRLF/控制字符测试。未降低全局质量阈值。

## 修改文件

| 位置 | 内容 |
| --- | --- |
| `.agents/skills/aegisos-init/` | SKILL、agents/openai.yaml、两份 references、三个 assets |
| 根 `pom.xml` | Boot、格式/Jupiter/静态与依赖扫描配置 |
| `aegisos-web/pom.xml` | Web/Validation 与 provided 静态分析注解 |
| `aegisos-boot/pom.xml` | Actuator、Prometheus、springdoc、测试依赖 |
| web `baseline/RequestIdFilter.java` | 请求 ID 和安全完成日志 |
| web `baseline/ApiProblems.java`、`ApiExceptionHandler.java` | 安全错误体与异常边界 |
| web `baseline/JsonBodyLimitFilter.java` | 有限 JSON 缓冲、超限 413 |
| web `baseline/WebBaselineConfiguration.java` | 明确过滤器顺序和装配 |
| boot `AegisOsApplication.java` | 显式 import web 基础配置 |
| boot `application.properties`、`application-dev.properties`、`application-metrics.properties` | 默认边界和可选环境 |
| boot 测试 `EngineeringBaselineTest.java`、`RequestIdFilterTest.java`、`LocalObservabilityTest.java` | HTTP、日志、线程上下文、监控行为 |
| 八个原有 `*Module.java` 占位类 | 仅统一 Google Java Style，无功能变更 |
| `scripts/check.sh`、`scripts/install-gitleaks.sh` | 共用门禁、官方固定包校验安装 |
| `.github/workflows/engineering.yml`、`.gitignore` | 三项独立 CI job、生成物/本地配置忽略 |
| `specs/004-engineering-baseline/{spec,plan,tasks}.md` | 本次独立工程规格和执行记录 |
| `README.md`、`AGENT.md`、`docs/engineering/` | 当前状态、开发指南和本报告 |

原有 `aegisos-provider/pom.xml` 的未提交格式修改保留原始字节；`.specify/feature.json` 保持 `specs/002-provider-abstraction`，没有覆盖 US-1 计划。九模块与十四模块长期设计差异已标明，未借初始化改写宪法。

## 实际验证

下表保留首次初始化及 Boot 3 修复阶段的验证记录；当前 Boot 4 的 17 项测试和安全扫描结果见文末增量记录。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Skill quick_validate | PASS，退出 0 | frontmatter、名称、脚手架检查 |
| `mvn spotless:apply` / 后续 validate check | PASS，退出 0 | 少量占位类及新增 Java 文件已规范化 |
| `mvn -pl aegisos-boot -am test` | PASS，退出 0 | 首轮 15 项通过，后续新增一项注入防护测试 |
| `mvn test` | PASS，退出 0 | 16 项，0 失败、0 错误、0 跳过 |
| `mvn clean package` | PASS，退出 0 | 父工程及九模块全部成功，Boot 可执行 JAR |
| `bash scripts/check.sh quality` | PASS，退出 0 | 全量测试、打包、Spotless、SpotBugs；没有未处理发现 |
| Gitleaks 8.30.1 官方包校验 | PASS，退出 0 | 官方 SHA-256 清单与下载包一致，本地 `.tools/` |
| `bash scripts/check.sh secrets` | PASS，退出 0 | Git 历史 4 commits 和工作目录均无发现；报告脱敏 |
| `bash scripts/check.sh dependencies` | FAIL，退出 1；漏洞分析已完成 | 安全版本修复后，17 项测试通过；告警由 105 项降至 17 项，达到 CVSS 7 的唯一告警由 54 项降至 12 项，均匹配 Spring Framework 6.2.19 |
| effective POM / XML / Shell / Skill 引用 | PASS | 版本展开、插件启用、脚本语法和资源路径检查 |
| 打包 JAR 默认模式演示 | PASS，退出 0 | 随机本机端口：health 200、未知路径 404/请求 ID、文档/指标关闭 |
| 打包 JAR dev,metrics 演示 | PASS，退出 0 | 文档和独立本机指标可用；env 关闭；JAR 无测试 Controller |
| 慢速 JSON 上传演示 | PASS | 发送未完成请求体，约 5.03 秒后收到 400，验证容器上传等待边界 |
| GitHub Actions 实际运行 | NOT_RUN | 工作流已落地，本次没有推送触发远端运行 |
| 真实 Provider、数据库、Prometheus/Grafana 平台 | N/A | 不属于工程初始化范围 |

16 项测试分布：EngineeringBaselineTest 11 项、LocalObservabilityTest 2 项、RequestIdFilterTest 3 项。覆盖正常/非法/并发请求 ID、MDC 恢复、CRLF 防护、安全 400/404/500、32 KiB 精确边界、固定长度/chunked 超限 413、超大 Header、健康最小暴露与文档/指标开关。

## 依赖安全验证遗留

首次执行时未提供可用的 `NVD_API_KEY`。真实扫描日志出现 `Error updating the NVD Data`，底层返回 `NvdApiException: Invalid API Key, length of 0 ...`；随后 RetireJS 的 `raw.githubusercontent.com` 数据下载超时，并以 `NoDataException: No documents exist` 结束，退出 1。该轮没有有效的完整漏洞分析结果，不能给出零漏洞结论。没有通过降 CVSS 阈值、禁更新、关闭 failOnError 或添加无依据抑制来绕过。后续密钥配置与重跑结果见下方记录。

常规在线更新需要在受控环境配置 NVD key，并确认 NVD 与 RetireJS 数据源可访问。扫描入口为：

```bash
bash scripts/check.sh dependencies
```

GitHub Actions 已保留强制扫描 job，key 从仓库 Secret 注入；数据或扫描失败继续阻断。网络数据的单次超时不等于整个数据库更新时限，CI job 总上限为 30 分钟。

本地执行日志保存在被忽略的 `target/engineering-evidence/`，包含 test/package/quality/secrets/dependencies、effective POM、启动演示与 Skill 校验。该目录为本次本地证据，不进入版本库；CI 运行会产生自己的报告。


### 2026-10-21 重试记录

按用户要求再次原样执行 `bash scripts/check.sh dependencies`，约 58 秒后仍退出 1；16 项测试通过，但漏洞分析因 NVD 更新失败、RetireJS 下载超时而未完成。

独立网络核实：NVD 匿名 curl 请求返回 HTTP 200；`raw.githubusercontent.com` 的 RetireJS 地址连接/TLS 超时，GitHub Contents API 返回 HTTP 200。因此不能将本次失败简单归因为 NVD 服务整体不可达。

进一步核查本机插件字节码和默认资源：Dependency-Check Core 13.0.0 的 `dependencycheck.properties` 含空值 `nvd.api.key=`，`NvdApiDataSource` 仅判断非 null，客户端随后发送空 `apiKey` Header，与扫描中的 `Invalid API Key, length of 0 ...` 对应。仓库没有启用 nvd-auth Profile，仍会触发该上游默认值问题。配置有效 key 可避开该路径；没有降低安全阈值或关闭检查。

本轮日志和探测元数据：`target/engineering-evidence/dependency-retry-2026-10-21.{log,json}`（本地忽略产物）。47 个原有 tracked 构建物均已恢复；该轮仍需有效 NVD key 和可达的 RetireJS 数据源后重跑，不能标记 PASS。

### 2026-10-21 配置密钥后重跑

用户提供的密钥已被 NVD 接受，API 返回本次更新包含 412,465 条记录；密钥仅通过隐藏输入和进程环境传递，没有写入项目文件、命令行参数或扫描日志。认证问题已排除，但分页请求反复超时，因此有控制地停止该次运行，退出 143；这不是一次通过的扫描。

随后使用 NVD 官方批量源。直接下载 2026 年文件时，服务在传输 4,872,667 / 36,949,962 字节后中断，因此停止该次运行（退出 143），保留完整文件后改用官方源支持的 HTTP Range 分段下载。最终取得 2002—2026 年和 modified 共 26 份数据，压缩后合计约 241.3 MiB；全部通过 gzip 完整性、解压前后大小及官方 `.meta` 中的 SHA-256 校验。modified 清单更新时间为 `2026-10-21T01:00:12-04:00`，即北京时间 13:00:12。没有缩减年份范围或关闭数据更新。

RetireJS 原始地址仍有超时，改用当天 13:52 从同一官方仓库 GitHub Contents API 取得的响应，解码后核对 Git blob SHA-1，得到 581,646 字节、76 个库条目的完整数据。原始地址的网络问题没有因复用缓存而被证明解决。

| 本次执行 | 结果 | 证据 |
| --- | --- | --- |
| 官方文件完整性校验 | PASS | `target/engineering-evidence/nvd-feeds/source.json`、`retirejs/source.json` |
| 临时指定本地官方文件的依赖扫描 | FAIL，退出 1；约 105.75 秒 | NVD 导入完成，NVD、RetireJS、CISA 分析完成，生成 HTML/JSON；CVSS 门禁触发 |
| 恢复默认数据源配置后，原样执行 `bash scripts/check.sh dependencies` | FAIL，退出 1；约 10.71 秒 | 正常复用刚更新的缓存，分析完成；同样因 CVSS 门禁触发失败 |
| 两次运行中的测试与 Maven package | PASS | 每次 16 项测试，0 失败、0 错误、0 跳过；失败发生在随后 aggregate 安全门禁 |

该历史阶段报告包含 54 个依赖分析条目，其中 8 个条目存在匹配发现；按漏洞标识去重共 105 项，其中 54 项按所提供 CVSS 版本的最高分达到 7，20 项标记为 CRITICAL。扫描匹配尚未逐项复核适用性与运行时可利用性，不能直接等同于 105 个可利用缺陷。

| 匹配依赖 | 达到 CVSS 7 的发现数 | 最高 CVSS |
| --- | --- | --- |
| Jackson Databind 2.19.4 | 2 | 8.1 |
| Log4j API 2.24.3 | 1 | 7.5 |
| Spring Boot 3.5.10 | 7 | 9.8 |
| Spring Core 6.2.15 | 18 | 9.8 |
| Tomcat Embed Core 10.1.50 | 25 | 9.8 |
| Swagger UI 5.32.2 中两份 DOMPurify 3.3.2 bundle | 每份 1，属于同一个漏洞 | 7.2 |
| Commons Lang 3.17.0 | 0 | 5.3 |

Sonatype OSS Index Analyzer 因未配置其独立凭据而由插件自动停用，日志已明确警告；本次不包含该数据源的验证。没有人为关闭分析器、提高 CVSS 门槛、关闭 failOnError、添加抑制规则或升级依赖来改变结果。下一步应复核 CPE/组件匹配及适用条件，再对确认受影响的依赖做版本修复。

最新报告：`target/dependency-check-report.html`、`target/dependency-check-report.json`。扫描日志、退出码和摘要分别保存在 `target/engineering-evidence/dependency-{verified-feeds,default-cached}-2026-10-21{.log,-status.json}`、`dependency-findings-summary-2026-10-21.json`。这些均为本地忽略产物。两次扫描均按各自启动前快照恢复 47 个已跟踪构建物；执行期间已有的 IDE 设置与自动编译变化保留。本次仅更新执行报告，没有提交或推送。

### 2026-10-21 依赖安全修复增量

用户授权实施版本修复。已升级 Boot 3.5.16、Jackson BOM 2.21.8、Tomcat embed 10.1.61、Log4j BOM 2.26.1、Commons Lang 3.21.0、springdoc 2.9.1 与 Swagger UI 5.33.2（内含 DOMPurify 3.4.16）。实际打包 JAR 的依赖已核对；没有新增 Maven 模块或业务实现。版本依据和剩余风险详见[依赖安全记录](dependency-security.md)。

新增 Swagger 页面、JS、CSS、初始化脚本与配置接口的真实 HTTP 验证，修正升级 WebJar 后 springdoc 仍查找旧版本资源而返回 404 的问题。当前共 17 项测试：EngineeringBaselineTest 11 项、LocalObservabilityTest 3 项、RequestIdFilterTest 3 项。

| 验证 | 当前结果 |
| --- | --- |
| `bash scripts/check.sh quality` | PASS，退出 0，约 29.05 秒；17 项测试无失败/错误/跳过，clean verify/package、Spotless、SpotBugs 通过 |
| `bash scripts/check.sh secrets` | PASS，退出 0；Git 历史 5 commits 和当前工作目录均无发现，报告脱敏 |
| `bash scripts/check.sh dependencies` | FAIL，退出 1；17 项测试通过，分析正常完成，生成 HTML/JSON；12 个唯一告警达到 CVSS 7 |
| 可执行 JAR 依赖与资源 | PASS；Jackson 家族对齐 2.21.8，三个 Tomcat embed 组件同为 10.1.61，dev Swagger 资源版本一致，测试类未进入生产 JAR |
| GitHub Actions | NOT_RUN；本轮没有提交或推送 |

最终扫描共 59 个依赖分析条目，唯一有匹配发现的条目为 Spring Core 6.2.19：17 个唯一告警，其中 12 个达到门禁阈值。六项 CRITICAL、六项 HIGH 是扫描数据的严重性标签，尚不等同于当前业务路径可利用性。原有 Boot、Tomcat、Jackson、Log4j、Commons Lang 与 Swagger UI 发现均已从最终报告中消除。本轮没有添加抑制规则或放宽门禁。

Spring 官方将相应 6.2.20 修复标为 Enterprise Support Only，而 Maven Central 的公开 6.2 版本止于 6.2.19。彻底替换这批受影响版本需进一步选择 Boot 4 / Framework 7 迁移或合法商业补丁；保持 Boot 3 的本轮修复没有完成安全验收。Sonatype OSS Index 仍因缺少独立凭据未覆盖。

本轮证据保存在被忽略的 `.tools/engineering-evidence/dependency-remediation-2026-10-21/`：`quality.log`、`dependencies.log`、`findings-summary.json`、`dependency-check-report.{html,json}`、`packaged-dependencies.json`、`vendor/` 官方快照，以及 `artifacts/` 中经过验证的可执行 JAR。此位置不受 Maven clean 影响；历史章节的 target 本地证据路径经后续 clean 已不再保证存在。

最初的临时备份误置于根 target，被 clean 连同部分旧本地证据清除，属于执行失误。原先未修改的历史构建物可按 Git 恢复；任务开始时已有的 IDE 自动编译字节无法声称逐一恢复。后续证据与恢复快照均放到构建目录外，源文件、IDE 设置和活动 US-1 元数据保持各自原有修改。本轮仅更新依赖、开发配置、验证测试与相关文档，没有提交或推送。

实际恢复了 37 个原先未修改的 tracked 构建文件；10 个原先已有编译变化的路径保留重新编译内容，其原始未提交字节不可恢复。具体清单为本轮证据目录的 `build-artifact-recovery.json`。新验证产物位于该目录的 `artifacts/`，不会用恢复的历史 JAR 冒充当前构建。

## 首次初始化的清理和复核

原有47个 tracked `target/` 文件在构建和启动演示后恢复原始字节，避免构建产物混进源码差异；新的输出与扫描报告被忽略。恢复的历史 JAR 不能作为本次交付二进制，开发启动前重新 `mvn clean package`。演示进程均已停止。

插件、依赖、过滤器注册和 CI job 没有重复节点；本次没有切换活动功能、初始化数据库、访问模型、提交、推送或发布。下一步按 US-1 既有 Spec/plan/tasks 开发 Provider，安全扫描遗留须持续跟踪。


### 2026-10-21 Boot 4 / Framework 7 迁移

用户明确选择公开开源升级路线。已采用 Boot 4.1.1 / Framework 7.0.9；Java 21、九个模块与业务范围不变。springdoc 为 3.1.1，Swagger UI 为 5.33.2。应用及测试迁移到 Jackson 3.1.5；springdoc/Swagger 兼容组件仍依赖 Jackson 2.21.5，两代依赖均由 Boot BOM 管理，打包清单已核对。

第一次迁移使用 BOM 默认 Tomcat 11.0.24，质量测试通过，但依赖扫描发现 11 个唯一匹配、9 个 CVSS 7 阻断项。依据 Apache 官方安全公告，将 core/el/websocket 统一显式管理为 11.0.27；再次执行原生门禁后通过。没有增加漏洞抑制、关闭分析器、跳过数据更新或降低失败阈值。

兼容调整：Web starter 改为 webmvc，测试使用 webmvc-test / actuator-test 和 `@AutoConfigureMetrics`；JSON 过滤器以 Jackson 3 的 `rebuild().build()` 保留映射配置。Boot 4 默认健康探针分组改变响应内容，已显式关闭分组，保留原先仅 `{"status":"UP"}` 的健康接口约定。原有异常脱敏、日志请求 ID、32 KiB JSON 和 16 KiB Header 行为全部通过真实 HTTP 测试。

| 检查 | 本轮最终结果 |
| --- | --- |
| `bash scripts/check.sh quality` | PASS，退出 0；17 项测试，0 失败/错误/跳过；Spotless、SpotBugs、九模块 clean verify/package 成功 |
| `bash scripts/check.sh dependencies` | PASS，退出 0；61 个依赖分析条目，0 个漏洞匹配，0 个 CVSS 7 阻断项 |
| `bash scripts/check.sh secrets` | PASS，退出 0；扫描 Git 历史 5 commits 和工作目录，无秘密发现 |
| 本地可执行 JAR | PASS；默认模式和 dev,metrics 模式启动验证通过；已核对 Framework 7.0.9、Tomcat 11.0.27、Jackson 3.1.5/2.21.5、springdoc 3.1.1；未打入测试 Controller |
| GitHub CI | NOT_RUN；未提交或推送触发远端验证 |
| Spring AI / Alibaba / SQLite 业务集成 | NOT_RUN；仅同步 US-1 计划、研究和任务，未添加或验证业务依赖 |

扫描报告时间为 2026-10-21 17:22（北京时间），正常复用当天 15:20 检查的 NVD 缓存，保持默认更新行为。Sonatype OSS Index 因缺少独立凭据未覆盖；本地门禁通过仅针对本次扫描覆盖的依赖与数据库，不能等同于完整安全保证。

US-1 现在以 Boot 4 基线、Spring AI 2.0.1 候选继续实施；原 1.1.2 的 RetryTemplate/OpenAI 选项代码方案不再视为适用。AI 2 使用官方 OpenAI Java 客户端，必须在后续实施中核对 SDK/HTTP 重试与 Tool API 并用 stub 验证；Alibaba 2.0.0-M1.1 仍为里程碑，首批不引入。

本轮证据位于 `.tools/engineering-evidence/boot4-migration-2026-10-21/`，保存最终日志、扫描 HTML/JSON、测试汇总、打包清单、官方发布依据及 `artifacts/` 内的新可执行 JAR，均在 Maven target 外。47 个 tracked 构建物在本轮首次 Maven 前已独立备份；恢复只针对构建物，不覆盖用户 `.idea` 或源文件修改。历史恢复产物不代表当前源码，运行请使用新证据 JAR 或重新打包。

本轮 47 个 tracked 构建物已从启动前快照逐字节恢复，SHA-256 全部一致；详见本地 `build-artifact-recovery.json`。用户 `.idea/misc.xml` 和原有工作区修改保留。


### speckit-implement：004 当前工作区复核

本轮按项目指南将默认/dev/metrics 应用和测试 Profile 改为 YAML，移除同名 properties；保留现有 US-1 Provider/审计配置。清除根指南中的明文模型凭据，改为环境变量引用，并补 Web 基础设施中文注释。未新增业务能力，未回滚工作区其他修改。

本轮 secrets 通过，Git 历史 5 commits 和工作目录均无发现。原生 quality 通过：91 项测试无失败/错误/跳过，其中工程基线 17 项；Spotless、SpotBugs 与九模块构建通过。dependencies 通过：101 个分析条目无漏洞匹配，安全门槛不变。新 JAR 默认与 dev,metrics 模式启动成功，约 5 秒慢速上传边界有效。47 个 tracked 构建路径已按本轮开始前快照恢复，活动功能元数据保留原值。

GitHub 登录检查失败，当前账号凭据无效，I009 保持 NOT_RUN/未完成；没有提交、推送或触发远端执行。全部本轮命令、状态、扫描覆盖限制和证据路径见 [004 验证记录](../../specs/004-engineering-baseline/validation.md)。YAML 迁移后首次构建需 `mvn clean package`，避免恢复的历史 properties 产物覆盖新配置。

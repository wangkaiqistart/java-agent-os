# 依赖安全修复记录

日期：2026-10-21。范围：用户授权实施依赖安全处理方案；保持 Java 21、九个 Maven 模块；用户进一步明确授权迁移 Boot 4 / Framework 7，不实现业务功能。

## 版本修复

| 依赖 | 修复前 | 当前版本 | 管理方式 |
| --- | --- | --- | --- |
| Spring Boot | 3.5.10 | 4.1.1 | 根 POM 的 Boot BOM 和打包插件统一版本 |
| Spring Framework | 6.2.15 | 7.0.9 | 跟随 Boot 4.1.1 BOM，采用公开开源修复 |
| Jackson | 2.19.4 | 应用 3.1.5；Swagger 兼容组件 2.21.5；annotations 2.21 | 由 Boot BOM 管理；不再导入旧 Jackson 2 覆盖 BOM |
| Tomcat embed | 10.1.50 | 11.0.27 | core、el、websocket 三个组件显式同版管理 |
| Log4j API/SLF4J 适配器 | 2.24.3 | 2.26.1 | Log4j BOM 在 Boot BOM 前导入；运行时继续使用 Logback |
| Commons Lang | 3.17.0 | 3.21.0 | 根 dependencyManagement |
| springdoc | 2.8.17 | 3.1.1 | 根属性、boot 模块引用 |
| Swagger UI | 5.32.2 | 5.33.2 | 根 dependencyManagement，dev 资源版本配置同步 |
| Swagger 中的 DOMPurify | 3.3.2 | 3.4.16 | 已检查 WebJar 实际 bundle 内容 |

应用代码和测试采用 `tools.jackson.databind` 的 Jackson 3；过滤器使用 `mapper.rebuild().build()` 保留 Boot 的模块配置。springdoc/Swagger 仍传递引入 Jackson 2 兼容组件，两代 core/databind 位于不同包名，由 Boot BOM 管理。不能断言“打包中没有 Jackson 2”；须核对每代实际版本和扫描结果。

Boot 4.1.1 默认管理 Tomcat 11.0.24，首次迁移扫描出现 11 个唯一匹配，其中 9 个达到 CVSS 7。依据 [Apache Tomcat 11 官方安全公告](https://tomcat.apache.org/security-11.html)及 Maven Central 发布元数据，将 core、el、websocket 显式统一到 11.0.27；未继续沿用 Tomcat 10 的覆盖。

项目导入 Boot BOM，并未继承 Boot starter parent。只声明同名属性不足以覆盖导入 BOM 内部版本，因此 Tomcat、Commons Lang、Swagger UI 通过显式依赖管理生效，Log4j BOM 优先导入。最终以可执行 JAR 的 `BOOT-INF/lib` 和完整扫描核验。

## 误匹配与抑制策略

原报告将 CVE-2026-34479 匹配到 `log4j-api`；[Apache 官方公告](https://logging.apache.org/security.html#CVE-2026-34479)列出的受影响组件是 `log4j-1.2-api`。当前应用未引入该桥接模块。此次通过 Log4j 版本对齐消除该告警，没有添加抑制规则。

所有分析器和 CVSS 7 门禁保持原配置；没有关闭数据更新、降低失败门槛或批量抑制 Spring Framework 告警。新增抑制规则必须限定组件与漏洞标识，记录官方依据、复核期限和失效条件。功能暂未启用不能替代供应商修复版本。

## 历史阶段：Spring Framework 6.2 的公开版本限制

本次核查 Maven Central 最高公开 6.2 版本为 6.2.19。官方公告将相关修复列为：

| 公告示例 | 6.2 修复版本 | 开源修复版本 |
| --- | --- | --- |
| [CVE-2026-47884：XsltView 路径限制](https://spring.io/security/cve-2026-47884) | 6.2.20，Enterprise Support Only | 7.0.9 |
| [CVE-2026-59283：SpEL 编译安全限制绕过](https://spring.io/security/cve-2026-59283) | 6.2.20，Enterprise Support Only | 7.0.9 |
| [CVE-2026-59313：MVC 函数式 SSE 流损坏](https://spring.io/security/cve-2026-59313) | 6.2.20，Enterprise Support Only | 7.0.9 |

剩余匹配涉及 XSLT、SSE、WebFlux、RSocket、SpEL 和数据绑定等条件。当前工程采用 MVC，尚未实现上述多数业务路径；这些条件说明需要进一步评估可利用性，不构成批量忽略受影响版本的依据。官方公告与 NVD 的严重性评级存在差异时，保留原扫描评分及阻断行为。

用户已选择 Boot 4 / Framework 7 开源路线，当前工程已迁移到 Boot 4.1.1 / Framework 7.0.9。下列为上一轮 Boot 3 的阻断记录，不能视为当前未修复项。Provider 计划、研究和任务已同步；AI 2 SDK 的实际行为仍留给 US-1 验证。

Boot 3 阶段报告中达到 CVSS 7 门槛的标识如下，当时均由原门禁阻断：

| 匹配条件类别 | 漏洞标识 |
| --- | --- |
| XsltView | CVE-2026-47884 |
| SSE 视图片段与 MVC 函数式 SSE | CVE-2026-47890、CVE-2026-59313 |
| WebFlux multipart、XML、函数式请求匹配、Jetty Cookie 与 WebSocket 异常 | CVE-2026-47885、CVE-2026-47891、CVE-2026-47892、CVE-2026-47889、CVE-2026-47893 |
| SpEL 运算与编译 | CVE-2026-47886、CVE-2026-59283 |
| RSocket | CVE-2026-47888 |
| 属性路径数据绑定 | CVE-2026-59282 |

## 历史阶段：Boot 3 行为验证与证据

增加 `LocalObservabilityTest.developmentSwaggerAssetsAndConfigurationAreServed`，通过真实 HTTP 验证 Swagger 页面、bundle、CSS、初始化脚本和配置接口。初次验证发现 springdoc 按旧 WebJar 版本查找资源，返回 404；同步 dev 中的 `springdoc.swagger-ui.version` 后通过。默认模式关闭文档的行为由既有测试覆盖。

质量与扫描使用原生入口：

```bash
bash scripts/check.sh quality
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

最终结果见[初始化执行报告](initialization.md)的依赖安全修复增量。质量门禁包含 17 项测试、Spotless、SpotBugs 和 Maven clean verify/package；漏洞扫描按原门槛独立给出结果。

| 检查或指标 | 修复前 | 最终结果 |
| --- | --- | --- |
| 唯一漏洞匹配 | 105 | 17，均匹配 Spring Core 6.2.19 |
| CVSS ≥ 7 的唯一匹配 | 54 | 12，依赖安全门禁仍 FAIL |
| quality | 原基线通过 | PASS，17 项测试通过 |
| secrets | 原基线通过 | PASS，Git 历史与工作目录均无发现 |

秘密扫描曾匹配到缓存的三个 Spring 官方页面内的网页配置内容；已改为保留公告可见正文、来源地址与原始 HTML SHA-256，清除无关页面脚本与属性后重扫通过，没有增加秘密扫描抑制规则。

本轮保留证据的位置为 `.tools/engineering-evidence/dependency-remediation-2026-10-21/`，包含质量、秘密、依赖日志，HTML/JSON 报告、汇总及官方公告正文摘录/发布元数据。该目录被 Git 忽略，位于所有 Maven target 之外。NVD 缓存仍使用本机既有官方数据库；没有在新日志或配置中写入 API key。Sonatype OSS Index 因缺少独立凭据仍未覆盖，不能据此宣称已完成该数据源验证。

## 构建物恢复限制

本轮最初将快照和部分本地证据放入根 target，随后被 Maven clean 清除，属于执行失误；这些旧文件无法宣称完整保留。后续快照、日志和扫描报告已移至 `.tools/engineering-evidence/`。Git 中的历史构建物可恢复，但任务开始时已有的 IDE 自动编译字节无法逐一恢复。源文件、IDE 设置和活动功能元数据不因此回滚。后续执行 clean 前必须将所需备份放在构建目录之外。

已将任务开始时未修改的 37 个 tracked 构建文件按 Git 字节恢复；另外 10 个原先已有编译变化的路径保留本轮重新生成内容，不能当作原字节恢复。清单见本地 `build-artifact-recovery.json`。已验证的新可执行 JAR 保存在本轮证据的 `artifacts/`，启动时使用该产物或重新 Maven 打包，不能把恢复的历史 JAR 当作当前版本。


## Boot 4 迁移与验收

当前实现为 Boot 4.1.1 / Framework 7.0.9 / Tomcat 11.0.27 / springdoc 3.1.1。测试 starter 改用 webmvc-test 和 actuator-test，指标测试改用 `@AutoConfigureMetrics`。Boot 4 默认开启 liveness/readiness 分组，已显式设置 `management.endpoint.health.probes.enabled=false`，继续保持默认 health 只返回状态。

现有真实 HTTP 测试覆盖默认关闭文档/指标、dev OpenAPI 与 Swagger 资源、独立 loopback Prometheus、请求关联、异常脱敏、32 KiB JSON（含 chunked）和 16 KiB Header。未增加业务 API 或 AI/数据库依赖。

本轮原始日志、完整扫描 HTML/JSON、打包版本清单与新 JAR 保存在 `.tools/engineering-evidence/boot4-migration-2026-10-21/`，与 Maven clean 隔离。47 个 tracked 构建物在首次构建前按当前字节独立快照，验证后恢复并校验 SHA-256。恢复的历史产物不代表当前源码版本，运行使用新证据 JAR 或重新打包。

最终门禁结果见[初始化报告](initialization.md)末尾 Boot 4 增量；Sonatype OSS Index 缺独立凭据的覆盖限制仍保留，远端 CI 尚未触发。


最终 quality、secrets、dependencies 门禁均已通过；17 项测试成功。依赖门禁已通过，报告时间为北京时间 2026-10-21 17:22；61 个依赖分析条目、0 个匹配、0 个 CVSS 7 阻断。正常使用当天 15:20 检查的 NVD 缓存，未禁用默认更新。原始阻断数依次为 54 → Boot 3 修复后 12 → Boot 4 默认 Tomcat 9 → Tomcat 11.0.27 修复后 0。这是扫描覆盖内的结果，不代表零未知风险。


## US-1 新增依赖与前次阻断（23:06，已于 23:23 解决）

本节覆盖后续 Provider 实施；上文 17:22 的 PASS 仅属于 Boot 4 迁移阶段，不能沿用为新增依赖的验证结果。

- Spring AI 2.0.1、OpenAI SDK core 4.49.0；SQLite JDBC 3.53.4.0；Hibernate core/community dialect 同为 7.4.5.Final。
- 新依赖最初引入 Kotlin 2.3.21 和旧 stdlib-common 1.9.10，扫描匹配 CVE-2026-53914（CVSS 9.8）。Kotlin BOM 升至 2.4.21；由于 common 的 pom/jar 类型差异，进一步管理 Okio/Okio JVM 3.16.4，消除旧 common JAR。最终依赖树与打包清单均已核对，没有添加抑制。
- 修复后的 91 项测试、九模块 clean package 和 Spotless/SpotBugs 通过；秘密扫描与展开产物哨兵检查见 [US-1 验证记录](../../specs/002-provider-abstraction/validation.md)。
- 最终依赖扫描返回 1：NVD API 更新抛出 UpdateException/NvdApiException，报告沿用当天 15:20 检查的数据库。101 个分析条目中没有匹配，但含更新异常，**安全门禁仍 FAIL**。Sonatype OSS Index 亦因缺少独立凭据未覆盖。

本机已为原生 DLL 分析安装并按 Microsoft 官方 SHA-512 校验 .NET 8.0.32 至忽略目录 `.tools/dotnet`，脚本可发现；不修改系统运行时。曾验证 modified feed 文件但年度元数据不完整，未成功更新、未保留实验镜像配置。需要通过环境或 CI Secrets 注入有效 NVD_API_KEY 并恢复更新后重跑 `bash scripts/check.sh dependencies`，不得将旧库扫描或下载成功标为 PASS。T051 保持未完成，暂不放行依赖 US-1 的 ReAct 开发。


## US-1 最终依赖门禁通过（23:23）

用户提供的 NVD 凭据已保存在本机 macOS 钥匙串，仅向扫描子进程注入 `NVD_API_KEY`。北京时间 23:19:15～23:23:06 重新运行原生 `bash scripts/check.sh dependencies`，退出码 0。NVD API 增量下载 2151/2151 完成，API Last Checked 更新为 23:22:57；101 个依赖分析条目、0 个漏洞匹配、0 个更新/分析异常。91 项测试全部通过，九模块及父 POM 构建成功。未放宽门槛、禁用更新、使用实验镜像或修改缓存时间。

T051 已完成，US-2 的 US-1 前置门禁已满足。Sonatype OSS Index 仍缺少独立凭据，因此不在本次已验证的数据源范围；真实厂商、CI 和部署未验证。详细证据见 [US-1 验证记录](../../specs/002-provider-abstraction/validation.md)。

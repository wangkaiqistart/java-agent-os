# 工程初始化执行报告

日期：2026-10-21。执行范围：review 并完善 `aegisos-init`，随后执行工程初始化。当前分支 `main`；本次未提交、推送或部署。

工程基础实现、测试、打包和启动演示已完成。**依赖漏洞安全验收尚未通过**，不能将本报告理解为已证明零漏洞或已具备业务 Agent 能力。

## 初始状态与处理

| 项目 | 执行前 | 本次结果 |
| --- | --- | --- |
| 工程 | 九模块、Java 21、Boot 3.3.5、占位类 | 九模块保留；按 US-1 已批准 plan 使用 Boot 3.5.10 |
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
| `bash scripts/check.sh dependencies` | FAIL，退出 1 | 实际执行 2 分 42 秒；NVD 更新失败、RetireJS 下载超时，未完成漏洞分析 |
| effective POM / XML / Shell / Skill 引用 | PASS | 版本展开、插件启用、脚本语法和资源路径检查 |
| 打包 JAR 默认模式演示 | PASS，退出 0 | 随机本机端口：health 200、未知路径 404/请求 ID、文档/指标关闭 |
| 打包 JAR dev,metrics 演示 | PASS，退出 0 | 文档和独立本机指标可用；env 关闭；JAR 无测试 Controller |
| 慢速 JSON 上传演示 | PASS | 发送未完成请求体，约 5.03 秒后收到 400，验证容器上传等待边界 |
| GitHub Actions 实际运行 | NOT_RUN | 工作流已落地，本次没有推送触发远端运行 |
| 真实 Provider、数据库、Prometheus/Grafana 平台 | N/A | 不属于工程初始化范围 |

16 项测试分布：EngineeringBaselineTest 11 项、LocalObservabilityTest 2 项、RequestIdFilterTest 3 项。覆盖正常/非法/并发请求 ID、MDC 恢复、CRLF 防护、安全 400/404/500、32 KiB 精确边界、固定长度/chunked 超限 413、超大 Header、健康最小暴露与文档/指标开关。

## 依赖安全验证遗留

当前环境没有可用的 `NVD_API_KEY`。真实扫描日志出现 `Error updating the NVD Data`，底层返回 `NvdApiException: Invalid API Key, length of 0 ...`；随后 RetireJS 的 `raw.githubusercontent.com` 数据下载超时，并以 `NoDataException: No documents exist` 结束，退出 1。因此没有有效的完整漏洞分析结果，不能给出零漏洞结论。没有通过降 CVSS 阈值、禁更新、关闭 failOnError 或添加无依据抑制来绕过。

需要在受控环境配置 NVD key，并确认 NVD 与 RetireJS 数据源可访问后，重新运行：

```bash
bash scripts/check.sh dependencies
```

GitHub Actions 已保留强制扫描 job，key 从仓库 Secret 注入；数据或扫描失败继续阻断。网络数据的单次超时不等于整个数据库更新时限，CI job 总上限为 30 分钟。

本地执行日志保存在被忽略的 `target/engineering-evidence/`，包含 test/package/quality/secrets/dependencies、effective POM、启动演示与 Skill 校验。该目录为本次本地证据，不进入版本库；CI 运行会产生自己的报告。

## 清理和复核

原有47个 tracked `target/` 文件在构建和启动演示后恢复原始字节，避免构建产物混进源码差异；新的输出与扫描报告被忽略。恢复的历史 JAR 不能作为本次交付二进制，开发启动前重新 `mvn clean package`。演示进程均已停止。

插件、依赖、过滤器注册和 CI job 没有重复节点；本次没有切换活动功能、初始化数据库、访问模型、提交、推送或发布。下一步按 US-1 既有 Spec/plan/tasks 开发 Provider，安全扫描遗留须持续跟踪。

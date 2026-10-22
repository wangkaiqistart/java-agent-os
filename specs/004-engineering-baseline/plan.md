# 实施计划：工程基础初始化

日期：2026-10-21。依据：[spec.md](spec.md)、宪法 1.0.1、aegisos-init。

## 宪法核对

九模块不变；core 不引入 Spring Web/模型/存储；boot 显式导入 web 基础配置，避免兄弟包漏扫描。初始化不调用 LLM/Tool，不改变 Provider 重试或审计契约；新文档使用中文。

## 技术与落点

- 根 POM：Boot 4.1.1、Spotless/google-java-format、Surefire、SpotBugs/Dependency-Check profiles。
- web：X-Request-ID/MDC 过滤器、安全 ProblemDetail、32 KiB JSON body 上限、显式配置导入。
- boot：Actuator、Micrometer Prometheus、springdoc 3.1.1 与 Boot 4.1.1 兼容版本；default/dev/metrics 配置；随机端口真实 HTTP 集成测试。
- scripts/check.sh：quality/secrets/dependencies 共用入口，缺工具非零返回；GitHub Actions 调同一脚本。
- .gitignore：运行数据、target 和本地敏感配置；不自动删除47个已 tracked 的旧构建文件。
- docs/engineering：API/开发约定与实际执行报告；更新根指南中已过时的工程状态，但不批量修订十四模块长期设计。

### 2026-10-21 依赖安全修复增量

用户已授权按扫描报告实施版本修复。升级 Boot 至 3.5.16；显式管理 Jackson BOM 2.21.8、Tomcat 三个 embed 组件 10.1.61、Log4j BOM 2.26.1、Commons Lang 3.21.0、springdoc 2.9.1 与 Swagger UI 5.33.2。Boot 默认 Jackson 2.21.4 仍匹配中危 CVE-2026-54515，因此采用同一版本线已发布的 2.21.8。项目使用 import BOM，不能只声明同名属性并假定已覆盖传递版本；须核对实际解析版本及打包内容。

上述 Boot 3 修复为历史阶段，仍有 12 个 Framework 阻断匹配；随后用户已明确批准 Boot 4 迁移。当前目标采用 Boot 4.1.1 BOM 管理的 Framework 7.0.9、Tomcat 11.0.27（BOM 默认 11.0.24 的新告警经官方公告核对后升级）、Jackson 3.1.5，去掉 Tomcat 10/Jackson 2 的旧覆盖，保留 Log4j/Commons Lang/Swagger 的安全版本管理。使用 MVC/Actuator 专用测试 starter 与 `@AutoConfigureMetrics`，应用过滤器和测试迁移到不可变的 Jackson 3 mapper。springdoc 使用 3.1.1，验证 dev WebJar 静态资源仍可访问。

US-1 的 Spring AI 2.0.1 仅作为候选，SDK 重试、Tool 开关和存储组合必须重新验证；不把 AI 1.x 或 Alibaba 里程碑视为已兼容。

复用现有 HTTP、日志、监控测试和 quality/dependencies/secrets 门禁，补充开发模式 Swagger 静态资源可用性的行为验证。构建前备份 47 个 tracked 产物，完成后恢复启动前字节；保存修复前后扫描报告，记录仍未修复的漏洞。

## 设计选择

JSON body 在受限缓冲后交给 MVC，支持 chunked body，未知长度同样限流；只对 JSON 进行重放，不破坏 multipart/form 的 Servlet 参数解析。请求 ID 在过滤器 finally 恢复先前 MDC，防止线程复用污染。安全错误统一生成，原始异常不写默认日志。

Boot 原生 logstash JSON 日志不额外增加第二套日志实现。默认管理端点只有 health；metrics Profile 的独立管理端口绑定127.0.0.1。OpenAPI/dev 明确 opt-in。测试 Controller 只在测试类路径，不进入生产 JAR。

## 验证

先用真实随机端口测试合法/非法 requestId、空白参数、异常敏感哨兵、未知路径、固定长度/chunked 超大 JSON、health 与管理端点边界、OpenAPI opt-in、本机 Prometheus。随后运行格式、全测试、打包、静态扫描和秘密扫描。依赖扫描保留失败关闭并记录外部数据源限制。


## speckit-implement 本轮收敛

当前工作区还含后续 US-1 的 Provider 和 SQLite 实现；保留其源码与行为，仅将已有应用/测试 Profile 配置迁为 YAML。工程基线仍不新增业务能力。复核已完成任务时以当前源码和新报告为准，不沿用初始化时的 17 项测试、61 条依赖作为当前全项目结果。

移除根指南中的明文模型凭据，仅保留环境变量引用。当前功能通过 `SPECIFY_FEATURE_DIRECTORY` 与 `SPECIFY_FEATURE_NO_PERSIST=1` 显式选择，不覆盖活动功能文件。独立验收记录写入本目录 `validation.md`；首次构建前备份 tracked target 到 `.tools/engineering-evidence/`，结束逐字节恢复。

GitHub CI 的执行需要有效登录及已发布的待验证代码。当前 `gh auth status` 失败，I009 保留未完成；本地门禁与远端验收分别报告。

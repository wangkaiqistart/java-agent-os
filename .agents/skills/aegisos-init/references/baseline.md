# AegisOS 基础建设约定

本文件是初始化建议。先服从当前用户范围、项目宪法和有效规格；选项不是对所有项目的硬性要求。

## 模块与配置

| 位置 | 基础工程职责 |
|---|---|
| 根 POM | 插件/版本、编码、Java release、测试发现和质量门禁 |
| aegisos-boot | 应用启动、Actuator、指标 registry、日志配置、运行环境参数 |
| aegisos-web | 请求关联过滤器、公共错误响应、异常映射、Web 校验和相应测试 |
| aegisos-core | 仅经规格批准且跨模块需要的契约；不要为了初始化加入框架类型 |
| 其他模块 | 保持既有职责；仅补必要测试/插件配置 |

当前启动类位于 `com.aegisos.boot` 时，默认组件扫描不会覆盖兄弟包 `com.aegisos.web`。初始化若增加 Web Bean，须选择显式 import 或有依据的包扫描配置，并以启动测试证明装配成功。不能只加依赖就认定 Bean 已生效。

## Java 与 Maven

- 保持 Java 21、UTF-8、父子 POM 及 Boot 可执行 JAR 约定。读取有效依赖管理，区分“当前 POM”与“plan 中未来版本”。
- Google Java Style 使用 Spotless + google-java-format；后者负责格式，不等于全部 Java 语义规范。命名、异常、不可变 DTO、资源管理等规则放入精简开发约定；已有 Checkstyle 则按需补充，避免与格式器冲突。
- PluginManagement 只管理版本/默认配置，通常不主动执行插件；必须在生效的 build/plugins 或 profile 中启用，并检查 effective POM。
- 有测试的模块使用 Jupiter 和已锁定 Surefire；启动测试可用 Boot Test。无测试占位模块可无测试，但新增行为所在模块必须发现并运行相应测试。
- 格式器默认 check；初次启用门禁可整理为通过所需的少量现有骨架，记录范围。已有大量业务代码时避免全仓重排，保留并识别原有工作区改动。

## 日志

- 复用 Boot 的 SLF4J/Logback，不再并列加入 Log4j2 等第二套实现。Boot 3.3 等版本可采用显式 Logback 配置，不能直接假设支持较新 Boot 的结构化日志属性。
- 基础字段：timestamp、level、logger、requestId、event；相关业务存在时才加入 sessionId/provider/model/tool，不初始化虚假值。
- Web 入口校验请求 ID 格式/长度或生成 UUID，写入响应 Header 与 MDC；拒绝换行注入，不无界信任客户端任意文本。返回前清理 MDC；异步任务显式传播并清理上下文，不能依赖线程局部变量自动跨线程。
- 不默认记录请求/响应正文、Prompt、Tool 参数、认证 Header、完整 URI 查询串或原始 SDK 异常。诊断只用安全字段白名单，不能依靠正则事后脱敏兜底。
- 可配置滚动与保留策略；日志目录由运行配置决定并在 Git 忽略，不覆盖用户已有规则。
- `llm_calls`/`tool_invocations` 的持久化属于后续对应功能，初始化不宣称日志已经满足业务审计。

## HTTP 与 API

- 复用 Spring MVC，不加第二个 HTTP Server 或无理由迁移 WebFlux；端口可由外部配置覆盖。线程/虚拟线程配置按当前 Boot 与项目范围验证。
- 明确 Header、请求体大小及超时边界；multipart 限制不等于普通 JSON 请求体限制，连接超时不等于整个业务请求截止时间。对实际容器和过滤器做边界测试，不只写配置。
- 资源路径、HTTP 方法、分页规则、时间格式和错误码参考现有规格。无现有公共响应约定时，优先 RFC 9457 ProblemDetail，加稳定 code/requestId；保留真实 HTTP 状态码，不能全部包成 HTTP 200。已有响应契约则保持兼容，不强制更换。
- 参数错误、安全的资源不存在、内部异常三种映射必须测试；响应不返回 SQL、栈、文件路径或原始异常消息。SSE/文件/健康检查不强制包装业务响应。
- OpenAPI 仅记录已实现接口；springdoc 版本必须匹配实际 Boot，文档和 Swagger UI 不默认开放到公网。没有业务端点时先落 API 约定，不生成虚构端点。

## 健康与监控

- boot 增加 Actuator；默认只暴露最小 health，详情 never。liveness/readiness 在规格要求时配置；不能把付费 LLM 调用作为健康探测。
- Micrometer 采集 JVM/HTTP 基础指标；若要求 Prometheus，显式加入 registry 并配置受保护的管理网络/访问边界。抓取端、Grafana、OTel collector 属于可选部署，不因装好 registry 就算平台部署完成。
- Metric tag 使用低基数字段，不把 sessionId/userId/requestId/完整 URL 当标签。分布式追踪为可选项，请求关联 ID 不等于完整分布式 trace。
- 模型正文/凭证不进入 observation；错误指标不携带原始异常文本。内部管理接口最小暴露不等于已经完成业务认证。

## 安全与 CI

- `.gitignore` 覆盖新增运行目录、target、SQLite WAL/SHM、本地密钥配置；先确认目标是否已经 tracked。被跟踪文件不会因新增忽略规则自动退出 Git，发现历史构建物只报告，不默认批量删索引或改写历史。
- Gitleaks 用于秘密扫描；SpotBugs 作为静态分析起点；OWASP Dependency-Check 检查依赖漏洞。它们覆盖不同问题，不能相互替代，也不能保证不存在漏洞。
- 普通门禁为格式/测试/打包；静态分析与安全扫描配置独立明确入口。CI 必须真正执行选定检查，依赖库数据更新失败记 FAIL/NOT_RUN，不当作“零漏洞”。
- NVD API key 等通过 CI secret 注入。抑制规则必须写明具体误报依据、范围和复核时间，不默认降阈值或忽略全部漏洞。
- 已有 CI 直接增量修改；没有 CI 时依据仓库托管平台选择，不能只因工具可用就创建 GitHub/GitLab 账号、工作流凭据或对外发布。
- 为 quality/secrets/dependencies 提供共享脚本入口，CI 与本地使用同一命令。安装工具时优先锁定版本的官方包或校验发布文件；不执行未经检查的远程 shell。非敏感公开依赖元数据可联网下载，秘密只从进程环境取。
- 聚合依赖扫描先在同一 reactor 完成 package，再直接调用 aggregate；配置与执行入口分离，避免父模块 verify 先于子模块构建。没有 NVD key 时不发送空密钥，配置了 key 时仅引用环境变量。
- 静态分析的明确误报只能按具体规则和位置处理，附依据和行为回归验证；不得降低全局阈值。测试中的指标导出须显式启用 Boot 的 AutoConfigureObservability，并防止测试 Controller 被其他上下文或生产 JAR 意外发现。
- 构建及扫描产物不包含秘密；CI 适当过滤报告再上传，临时秘密哨兵仅在测试内存中生成，不能写进版本化测试资源。

## 参考来源

- [Google Java Style](https://google.github.io/styleguide/javaguide.html)
- [Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)（选择实际 Boot 对应版本）
- [Micrometer](https://docs.micrometer.io/micrometer/reference/)
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
- [SpotBugs Maven](https://spotbugs.github.io/spotbugs-maven-plugin/)
- [Dependency-Check Maven](https://dependency-check.github.io/DependencyCheck/dependency-check-maven/)
- [Gitleaks](https://github.com/gitleaks/gitleaks)

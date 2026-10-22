# 工程开发指南

本指南描述工程基础设施。Provider、ReAct、Memory、Tool 和业务 API 按各自功能规格开发。当前宪法及根 POM 使用九模块，Boot 按用户授权的安全修复升至 4.1.1，Java 为 21；具体覆盖版本、验证与残留见[依赖安全记录](dependency-security.md)。

## 构建和启动

在仓库根执行，需要 Java 21、Maven 3.9.x：

```bash
java -version
mvn -version
mvn clean test
mvn clean package
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar
curl -i http://127.0.0.1:8080/actuator/health
```

默认仅监听 `127.0.0.1:8080`，可用 `SERVER_PORT` 改端口。`SERVER_ADDRESS` 可以改变应用监听地址；对外部署前须另行设计认证授权与网络访问控制。基础初始化没有实现登录或业务权限。

历史提交已跟踪部分 `target/` 构建产物，`.gitignore` 不能自动解除跟踪。构建后避免提交这些生成物；清理历史索引需要独立处理。初始化执行保留了原始字节，实际构建和启动证据见报告；启动前请重新打包。

执行 `clean` 前，备份和需要长期保留的日志必须放在所有 Maven `target/` 目录之外，例如被忽略的 `.tools/engineering-evidence/`。根工程的 `target/` 也会被清理，不能作为快照存放位置。

## 配置文件

应用配置统一使用 YAML：`aegisos-boot/src/main/resources/application.yml` 保存默认边界，`application-dev.yml` 和 `application-metrics.yml` 按 Profile 覆盖。Provider 仅填写 `credential-ref: env:DEEPSEEK_API_KEY`，真实值来自环境或受控密钥存储。不要在同一资源目录保留同名 `.properties` 配置，以免其更高优先级覆盖 YAML。此次迁移后首次构建必须执行 `mvn clean test` 或 `mvn clean package`，清除旧 `target/classes` 中的 properties；已恢复的历史构建物仅用于保留工作区原始状态，不能作为当前可运行版本。

## 可选 Profile

```bash
# 开发环境：启用 OpenAPI 与 Swagger UI
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev

# 指标：增加独立本机管理端口，可同时使用 dev,metrics
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --spring.profiles.active=metrics
```

| 环境 | 接口 | 行为 |
| --- | --- | --- |
| 默认 | `/actuator/health` | 仅返回 `{"status":"UP"}`，不含组件详情 |
| 默认 | env/configprops/heapdump、Prometheus、文档 | 不开放，返回 404 |
| dev | `/v3/api-docs`、`/swagger-ui/index.html` | 明确启用；当前没有业务端点 |
| metrics | `127.0.0.1:9091/actuator/prometheus` | JVM、进程、HTTP 等 Micrometer 指标 |
| metrics | 应用端口上的 `/actuator/*` | 管理接口转移到独立端口 |

`MANAGEMENT_SERVER_PORT` 可调整指标端口；管理监听地址固定为 `127.0.0.1`。本次未部署 Prometheus 抓取端、Grafana 或链路追踪平台。指标标签禁止使用 requestId/sessionId/userId 等高基数字段。

Swagger UI WebJar 版本由根 POM 的 `swagger-ui.version` 管理；dev 配置的 `springdoc.swagger-ui.version` 必须同步，否则 springdoc 可能查找旧资源路径并返回 404。`LocalObservabilityTest` 通过真实 HTTP 校验页面、JS、CSS、初始化脚本和文档配置，覆盖这条兼容边界。

## HTTP 和日志约定

- `X-Request-ID` 接受 1～64 个 ASCII 字符，首字符为字母或数字，其余可含 `._-`；非法或缺失时生成 UUID。响应头、ProblemDetail 和日志共享同一个 ID。
- 默认同步 MVC 请求使用 MDC，在 finally 恢复先前上下文。后续异步、SSE 和任务线程需要独立设计上下文传播，不能假设 ThreadLocal 自动继承。
- 日志使用 Boot 原生 logstash JSON：时间、级别、logger、requestId，以及请求完成 event/status/durationMs。不记录正文、Prompt、参数、认证 Header、完整 URI 或原始异常。诊断日志不替代持久化业务审计。
- 错误采用 RFC 9457 `application/problem+json`，保留 HTTP 状态，增加稳定 `code` 和 `requestId`。400/404/413/500 分别对应 INVALID_REQUEST/NOT_FOUND/PAYLOAD_TOO_LARGE/INTERNAL_ERROR；不把异常消息、SQL 或栈返回给调用方。`instance` 为安全固定值 `/`。
- JSON 请求体上限为 32768 **字节**，配置项 `aegisos.http.max-json-body-bytes` 允许 1 字节～1 MiB；固定长度及 chunked 都受限制。该过滤器只缓冲 JSON，不替代未来文件上传、表单或压缩解码的专用边界。
- 应用 JSON 使用 Jackson 3；springdoc/Swagger 的 Jackson 2 兼容组件由 Boot BOM 管理。默认健康探针分组显式关闭，health 仅返回状态。Header 上限 16 KiB，Tomcat 连接/上传等待 5 秒、keep-alive 等待 20 秒。容器在过滤器之前拒绝的协议或 Header 错误可能没有统一错误体和请求 ID。连接等待配置不等于 Agent 业务执行截止时间。

示例错误：

```json
{
  "type": "urn:aegisos:error:not_found",
  "title": "Not Found",
  "status": 404,
  "detail": "Not Found",
  "instance": "/",
  "code": "NOT_FOUND",
  "requestId": "generated-or-validated-id"
}
```

## Java 和 API 开发约定

使用 Google Java Style、UTF-8、Java 21。类按模块职责放置；core 不依赖 Web/Provider/存储实现。依赖稳定契约，使用不可变 DTO 和 Bean Validation；资源用 try-with-resources 关闭。异常只在边界转为安全错误，日志字段按白名单增加。API 资源路径、分页、鉴权和业务时限须先在相应 Spec 定义，不创建占位业务接口。

当前启动类在 `com.aegisos.boot`，兄弟包不会自动扫描；web 基础配置通过显式 `@Import` 装配。测试 Controller 仅通过测试 import 注册，不进入生产 JAR。

## 本地和 CI 共用的门禁

```bash
mvn spotless:check                  # 每次 validate 也会执行
mvn spotless:apply                  # 主动格式修复，先检查变更范围
bash scripts/check.sh quality        # clean verify + SpotBugs，含测试和打包
bash scripts/install-gitleaks.sh     # 官方 8.30.1 + SHA-256 校验，本地 .tools/
bash scripts/check.sh secrets        # Git 历史 + 当前工作目录，输出脱敏报告
bash scripts/check.sh dependencies   # OWASP Dependency-Check 聚合扫描
```

Gitleaks 可通过 `GITLEAKS_BIN` 指定受控二进制，否则优先 `.tools/gitleaks`，再读取 PATH；缺工具返回非零。扫描报告保留本地并被 Git 忽略，不向日志输出秘密原文。

Dependency-Check 为 13.0.0，入口先在同一 Maven reactor 打包，再执行聚合扫描；security Profile 本身只提供配置。CVSS >= 7 阻断，数据更新失败也阻断；单次连接/读取超时为 10/60 秒，NVD 重试最多 2 次。首次完整漏洞数据库更新可能耗时较长。`NVD_API_KEY` 通过进程环境或 GitHub Actions Secrets 注入，禁止命令行明文传密钥；非空 API key 存在时脚本额外启用 nvd-auth Profile，否则不启用。插件 13.0.0 的空默认密钥曾触发 NVD 认证错误，在线更新应提供有效密钥；刚更新的本地缓存可按插件正常有效期复用，这不证明无密钥冷启动可用。API key 有助于速率限制，但不保证网络可达。

GitHub Actions 的三个独立 job 调用相同入口，依赖扫描 job 上限 30 分钟；没有 continue-on-error。Secret 扫描读取完整 Git 历史。CI 的真实执行结果须由推送后的运行确认，本地测试不能替代它。

SpotBugs 对已验证的请求 ID 回显存在污点误报：仅在该过滤方法上排除 `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER`，依据为完整 ASCII 白名单和 CRLF/控制字符回归测试（2026-10-21）。若放宽白名单或新增 Header 写入，必须复核该排除。未降低全局静态检查阈值。

每个用户故事必须有行为测试和 Maven test/package 验证，测试发现数不能为零后声称业务通过。受网络或数据源阻塞的安全扫描单列 FAIL/NOT_RUN，禁止声称零漏洞。真实业务审计和 Tool Sandbox 在后续规格中实现。


### Provider 依赖扫描前提

US-1 的 SQLite JAR 携带原生 DLL，Dependency-Check 分析需要 .NET 8 运行时；可使用系统安装，或放入 `.tools/dotnet`（脚本会自动发现）。本机使用经 Microsoft 官方 SHA-512 校验的 8.0.32，不属于 Java 应用运行依赖。US-1 已于 2026-10-21 23:23 使用有效环境密钥完成 NVD API 更新并通过依赖门禁。保持默认更新、原有 CVSS 门槛和 `nvd-auth` 环境引用，不提交凭证；0 个漏洞匹配不能代替数据更新成功。Provider 配置、测试与门禁状态见 `specs/002-provider-abstraction/quickstart.md` 和 `validation.md`。

本机 NVD 凭据保存在 macOS 钥匙串：service `io.aegisos.nvd-api-key`、account `NVD_API_KEY`；需读取后注入扫描进程环境，不能放入 Maven 参数。当前本地忽略目录提供 `python3 .tools/us1-evidence/scan_with_keychain.py` 复用入口；这是本机辅助脚本，清理后应先确认存在，CI 仍通过 Secrets 注入。

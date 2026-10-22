# 工程基线实施验证

日期：2026-10-21。入口：`$speckit-implement 004-engineering-baseline`。

## 范围和前置检查

显式环境覆盖选中本功能，`check-prerequisites.sh --json --require-tasks --include-tasks` 返回本目录及 `tasks.md`。本功能没有独立 checklists；`.specify/extensions.yml` 不存在，无前置/后置扩展 hook。活动功能元数据保留原值 `specs/005-provider-course-alignment`。

本轮复核 I001～I008、S001～S004、M001～M005 的现有实现，完成 YAML、中文注释与凭据治理收敛。后续 US-1 源码及测试已存在，不由本轮新增或回滚；测试、依赖条目数包含这些既有实现。

## 本轮修改

- `aegisos-boot/src/main/resources/application.yml`、`application-dev.yml`、`application-metrics.yml` 替代原 properties，保留监听、超时、HTTP 边界、日志、健康、文档、指标和已有 Provider/审计配置。
- 测试 Profile 改为 `application-provider-test.yml`，保留原有开关。
- Web 基础设施补充中文注释，不改变执行逻辑。
- 移除根指南中的明文模型凭据，保留环境变量引用；不添加扫描抑制或回显凭据。
- 补充编辑器忽略规则和开发说明，保留已 tracked 的 IDE/构建文件及其他源文件修改。

## 实际验证

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| quality | PASS，退出 0 | 九模块及父工程 clean verify，Spotless/SpotBugs；91 项测试，0 失败/错误/跳过 |
| 工程基线行为 | PASS | EngineeringBaselineTest 11、LocalObservabilityTest 3、RequestIdFilterTest 3，共 17 项 |
| dependencies | PASS，退出 0 | 101 个依赖分析条目，无漏洞匹配；CVSS 7、failOnError 与默认自动更新保持启用 |
| secrets | PASS，退出 0 | Git 历史 5 commits 与工作目录均无发现；未新增抑制规则 |
| 生产 JAR | PASS | 打包中默认/dev/metrics 配置均为 YAML，无旧同名 properties 或测试 Controller；后续 package 与已验证 JAR 的 SHA-256 一致 |
| 默认启动 | PASS | 临时 SQLite、health 仅 UP、未知端点 404 ProblemDetail/requestId、文档/指标关闭，无外部 LLM 调用 |
| dev,metrics 启动 | PASS | OpenAPI 与 Swagger bundle 200，指标仅在独立 loopback 管理端口可用，env 不开放 |
| 慢速上传 | PASS | 未完成 JSON 请求体约 5.006 秒后 HTTP 400；此边界不等同于 Agent 执行截止时间 |
| ignore / Shell | PASS | target/class/JAR、工具、凭据及运行目录忽略生效；共享脚本通过 bash -n |
| tracked 构建物恢复 | PASS | 首次 Maven 前在 target 外备份 47 个路径，完成后逐项恢复，存在状态及 SHA-256 全部一致 |
| 活动功能元数据 | PASS | 与执行前字节一致 |
| GitHub CI（I009） | NOT_RUN | gh auth status 退出 1，钥匙串中的 GitHub 登录凭据无效；本轮未提交、推送或触发远端工作流 |

NVD API 正常复用有效缓存，Last Checked 为北京时间 2026-10-21 23:22:57；本次报告生成于 23:51:56。凭据从 macOS 钥匙串读取，仅注入扫描子进程环境，不写入命令参数或日志。Sonatype OSS Index 因缺少独立凭据未覆盖；无漏洞匹配仅表示本次扫描覆盖内的结果。

## 证据与遗留

本轮证据目录：`.tools/engineering-evidence/004-implement-20261021-234919`，包含日志、测试汇总、完整依赖报告、配置/依赖打包清单、启动检查结果、构建物恢复记录与 `artifacts/aegisos-boot-0.1.0-SNAPSHOT.jar`。该目录位于所有 Maven target 之外，被 Git 忽略。

历史构建物按本轮启动前状态恢复后，不代表当前源码。首次运行 YAML 版本前执行 `mvn clean package`，或直接运行上述已验证 JAR，避免旧 target/classes 的 properties 覆盖新 YAML。

I009 保持未完成。维护者需恢复 GitHub 登录，并发布本轮待验证代码后运行工程门禁 workflow；检查其 quality/secrets/dependencies 及上传报告，才可勾选 I009。本地工作已验证，功能整体不宣称全部任务完成。此处不替代远端 CI、生产部署或真实厂商业务验收。

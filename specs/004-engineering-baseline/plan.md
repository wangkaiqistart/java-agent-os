# 实施计划：工程基础初始化

日期：2026-10-21。依据：[spec.md](spec.md)、宪法 1.0.1、aegisos-init。

## 宪法核对

九模块不变；core 不引入 Spring Web/模型/存储；boot 显式导入 web 基础配置，避免兄弟包漏扫描。初始化不调用 LLM/Tool，不改变 Provider 重试或审计契约；新文档使用中文。

## 技术与落点

- 根 POM：Boot 3.5.10、Spotless/google-java-format、Surefire、SpotBugs/Dependency-Check profiles。
- web：X-Request-ID/MDC 过滤器、安全 ProblemDetail、32 KiB JSON body 上限、显式配置导入。
- boot：Actuator、Micrometer Prometheus、springdoc 2.x 与 Boot 3.x 兼容版本；default/dev/metrics 配置；随机端口真实 HTTP 集成测试。
- scripts/check.sh：quality/secrets/dependencies 共用入口，缺工具非零返回；GitHub Actions 调同一脚本。
- .gitignore：运行数据、target 和本地敏感配置；不自动删除47个已 tracked 的旧构建文件。
- docs/engineering：API/开发约定与实际执行报告；更新根指南中已过时的工程状态，但不批量修订十四模块长期设计。

## 设计选择

JSON body 在受限缓冲后交给 MVC，支持 chunked body，未知长度同样限流；只对 JSON 进行重放，不破坏 multipart/form 的 Servlet 参数解析。请求 ID 在过滤器 finally 恢复先前 MDC，防止线程复用污染。安全错误统一生成，原始异常不写默认日志。

Boot 原生 logstash JSON 日志不额外增加第二套日志实现。默认管理端点只有 health；metrics Profile 的独立管理端口绑定127.0.0.1。OpenAPI/dev 明确 opt-in。测试 Controller 只在测试类路径，不进入生产 JAR。

## 验证

先用真实随机端口测试合法/非法 requestId、空白参数、异常敏感哨兵、未知路径、固定长度/chunked 超大 JSON、health 与管理端点边界、OpenAPI opt-in、本机 Prometheus。随后运行格式、全测试、打包、静态扫描和秘密扫描。依赖扫描保留失败关闭并记录外部数据源限制。

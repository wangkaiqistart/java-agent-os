# 功能规格：工程基础初始化

日期：2026-10-21。来源：用户要求 review 并完善 aegisos-init，然后执行完成初始化。

## 范围与决策

保留宪法九模块。使用 Java 21，沿用 US-1 计划已批准的 Boot 3.5.10 基线；不实现 Provider、ReAct、Memory、Tool、Session、人工或电话业务。该功能独立记录，不修改活动 US-1 元数据或其任务完成状态。

## 用户故事与验收

1. 开发者能构建和检查工程：Google Java Style 格式检查、JUnit Jupiter、SpotBugs 与完整 Maven 构建有可执行入口；新增行为存在真实测试，缺测试不能算验证成功。
2. 运维能启动和诊断服务：默认 loopback 监听；请求携带/生成合法 X-Request-ID，日志采用 JSON 安全元数据；health 不显示内部详情，其他管理端点默认关闭。metrics Profile 通过独立本机管理端口提供 Prometheus，不访问外部模型。
3. API 使用者收到稳定错误：使用 RFC 9457 ProblemDetail，包含 code/requestId，保留 HTTP 状态；400/404/413/500 不回显正文、原始异常或栈。JSON 请求体最多 32 KiB，包括无 Content-Length 的请求；请求 Header 上限 16 KiB。
4. 维护者可执行安全门禁：共享脚本与 GitHub CI 执行秘密、静态分析及依赖扫描；工具/数据源不可用须失败，不能标为零漏洞。OpenAPI 仅在显式 dev Profile 启用，不创建业务端点。

## 边界

请求连接等待限制与业务执行截止时间不同；本次配置容器连接/上传等待，暂不定义尚不存在的 Agent 业务截止时间。请求关联不是分布式 trace。诊断日志不是 llm_calls/tool_invocations 持久审计。无登录认证，默认 loopback 仅用于开发，外网部署需要独立认证授权设计。

## 完成标准

聚焦 HTTP/日志/监控测试、完整 mvn test、mvn clean package、静态分析、秘密扫描与启动演示有实际结果。依赖扫描如受数据源限制，明确标为 FAIL/NOT_RUN 并保留 CI 强制门禁，不宣称安全验收完成。完整结果写 docs/engineering/initialization.md。

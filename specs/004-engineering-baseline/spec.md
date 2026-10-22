# 功能规格：工程基础初始化

日期：2026-10-21。来源：用户要求 review 并完善 aegisos-init，然后执行完成初始化。

## 范围与决策

保留宪法九模块。使用 Java 21，使用用户批准的 Boot 4.1.1 / Framework 7.0.9 基线；不实现 Provider、ReAct、Memory、Tool、Session、人工或电话业务。该功能独立记录，不修改活动 US-1 元数据或其任务完成状态。

2026-10-21 安全修复补充：用户授权落实依赖安全处理方案，工程基线升至 Boot 3.5.16，并在根 POM 集中管理兼容的 Tomcat、日志 API、Commons Lang 和 Swagger UI 安全版本。保持九模块、Java 21 与 CVSS 7 门禁；扫描匹配须核实官方公告，只有有证据的组件误匹配才允许精确抑制。无法取得兼容修复版本的真实漏洞继续阻断，不以功能暂未启用代替版本修复。

2026-10-21 迁移补充：用户明确选择 Boot 4 / Framework 7 路线。采用公开稳定 Boot 4.1.1 / Framework 7.0.9、Jackson 3、Tomcat 11、springdoc 3；迁移已有源码/测试与装配，保持原 HTTP、日志、健康和指标边界。同步当前基线与 US-1 技术计划，不在本次实现 Provider/数据库业务。质量、秘密、依赖门禁均需重新执行，禁止降低 CVSS 阈值或跳过数据更新。

## 用户故事与验收

1. 开发者能构建和检查工程：Google Java Style 格式检查、JUnit Jupiter、SpotBugs 与完整 Maven 构建有可执行入口；新增行为存在真实测试，缺测试不能算验证成功。
2. 运维能启动和诊断服务：默认 loopback 监听；请求携带/生成合法 X-Request-ID，日志采用 JSON 安全元数据；health 不显示内部详情，其他管理端点默认关闭。metrics Profile 通过独立本机管理端口提供 Prometheus，不访问外部模型。
3. API 使用者收到稳定错误：使用 RFC 9457 ProblemDetail，包含 code/requestId，保留 HTTP 状态；400/404/413/500 不回显正文、原始异常或栈。JSON 请求体最多 32 KiB，包括无 Content-Length 的请求；请求 Header 上限 16 KiB。
4. 维护者可执行安全门禁：共享脚本与 GitHub CI 执行秘密、静态分析及依赖扫描；工具/数据源不可用须失败，不能标为零漏洞。OpenAPI 仅在显式 dev Profile 启用，不创建业务端点。

## 边界

请求连接等待限制与业务执行截止时间不同；本次配置容器连接/上传等待，暂不定义尚不存在的 Agent 业务截止时间。请求关联不是分布式 trace。诊断日志不是 llm_calls/tool_invocations 持久审计。无登录认证，默认 loopback 仅用于开发，外网部署需要独立认证授权设计。

## 完成标准

聚焦 HTTP/日志/监控测试、完整 mvn test、mvn clean package、静态分析、秘密扫描与启动演示有实际结果。依赖扫描如受数据源限制，明确标为 FAIL/NOT_RUN 并保留 CI 强制门禁，不宣称安全验收完成。完整结果写 docs/engineering/initialization.md。


## 本轮配置与验证收敛

按项目指南约定，应用默认、dev、metrics 与测试 Profile 使用 YAML；移除同名 properties 配置，凭据仅使用环境变量或受控密钥存储引用。既有配置含后续 US-1 内容时保留其语义，本功能不重新实现或回滚其他故事。

本地完整门禁结果与远端 CI 结果分别记录；远端登录或发布条件未满足时，I009 保持未完成，不能以历史或本地测试替代。

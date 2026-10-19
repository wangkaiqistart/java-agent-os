# Provider Java 接口契约

**状态**：计划契约 | **归属**：`aegisos-core` | **字段**：[数据模型](../data-model.md)

US-1 暴露供其他模块调用的 Java 能力，不新增 HTTP/CLI 业务入口。以下签名用于约束实现，不是已存在代码。

## ProviderService

```java
ModelResponse chat(ModelRequest request) throws ProviderException;
```

- 同步、非流式调用；服务为每个逻辑请求生成稳定 requestId，成功和失败均返回该 ID。
- providerName/modelName 必填且精确匹配，不 trim 后猜测、不选择默认项，不 fallback。
- 校验失败不发模型请求；审计可写时一条 REJECTED，否则返回审计不可用。
- 请求只包含 core 类型，响应含所选身份、文本/结构化 Tool 调用、结束信息和可空用量。
- 未知/无效模型在本地允许集合校验中拒绝；上游模型已下线等情况转换为 MODEL_UNAVAILABLE，不尝试其他模型。
- 支持 schema 时转换/透传，无法支持则明确 UNSUPPORTED_TOOL_SCHEMA，禁止静默丢弃。不在 Provider 执行工具，也不自动请求下一轮。
- 同时出现文本与 toolCalls 时均返回；只有 toolCalls 的响应合法。空内容且无工具调用作为 PROVIDER_PROTOCOL_ERROR。

## 安全错误

ProviderException 只携带安全 `ProviderFailure`，禁止异常 message、toString 或日志沿用含正文/认证信息的上游原始异常。调用方不能依赖人类消息判断错误。

| code | 外呼 | 外层自动重试 |
|---|---|---|
| PROVIDER_REQUIRED / MODEL_REQUIRED | 无 | 否 |
| UNKNOWN_PROVIDER / MODEL_UNAVAILABLE | 本地拒绝时无；上游报告模型不可用时有 | 否 |
| PROVIDER_UNAVAILABLE / INVALID_CONFIGURATION | 无 | 否 |
| INVALID_REQUEST / UNSUPPORTED_TOOL_SCHEMA | 本地校验时无 | 否 |
| AUTHENTICATION_FAILED | 有 | 否 |
| PROVIDER_TIMEOUT / TEMPORARY_NETWORK_ERROR | 可能已发送 | 仅有额度时 |
| RATE_LIMITED / PROVIDER_HTTP_ERROR / PROVIDER_PROTOCOL_ERROR | 有 | 否 |
| AUDIT_UNAVAILABLE | 取决于故障阶段 | 否 |

400/401/403/429/5xx 都不能借用 SDK 默认策略变成可重试；本次仅用户确认的临时网络/超时可重试。TLS 证书错误、错误 URL/DNS 配置不视为临时网络错误；无法判定时保守地不重试。连接拒绝/连接重置/读写超时等具体分类须有聚焦测试。

`externalOutcome`：NOT_SENT（该逻辑请求尚无外呼）、RESPONDED（收到了上游响应）、UNKNOWN（可能已处理但无法确认）。后续尝试前审计失败时，综合前面尝试的事实，不能把整个逻辑请求标记 NOT_SENT。

## 重试编排

1. 第一次尝试编号为 1，最多总尝试次数为 `1 + maxRetries`；未配置 maxRetries 为 0。
2. 每次外呼先 begin 并提交，客户端内部总尝试次数固定为 1。
3. 返回或异常后 complete；写入失败立即结束，不重发。
4. 仅失败已持久化、类别可重试且额度未耗尽时，等待 100ms 固定间隔后再次 begin。测试中替换等待器，不实际长时间等待。
5. 不做 hedge、不切 Provider/模型；最终错误保留实际 attemptCount 与稳定错误类别。

有限重试并不保证上游幂等；超时后重试可能重复计费，这是用户选择可配置重试的成本边界。调用方收到 UNKNOWN 时应检查 requestId，不进行隐藏的补偿发送。

## LlmCallAuditStore

```java
AuditHandle begin(AuditStart start) throws AuditUnavailableException;
void complete(AuditHandle handle, AuditCompletion completion)
    throws AuditUnavailableException;
void reject(AuditRejection rejection) throws AuditUnavailableException;
```

- core 定义接口/参数；storage 实现；Provider 只依赖接口，不能直接调用 JPA Repository。
- begin 返回代表 STARTED 已提交且独立连接可读；complete/reject 返回代表终态已提交，不是仅调用 save/flush。
- begin/complete 为同一记录，reject 直接插入 attempt_no=0 的终态；数据库唯一约束防止重复写入。
- complete 只能从 STARTED 转终态，条件更新失败作为审计错误，不伪称成功。
- 审计异常本身不携带 SQL、连接串或消息正文；无自动修复/重放接口。

## 必须验证的契约

两个不同名称、相同适配器的 stub 只能收到各自指定请求；缺模型不得带出 SDK 默认模型；模型返回 Tool 调用后执行计数为零；底层网络发送计数符合 N+1；SDK 日志和异常不泄密。最小 boot 测试装配 Provider 和审计端口即可运行，不需要 AgentService/ReActLoop/MemoryService/ToolExecutor/Web Controller。

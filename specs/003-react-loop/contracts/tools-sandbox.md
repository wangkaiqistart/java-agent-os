# 工具、Sandbox 与审计契约

**性质**：待实施；FR-005、006、008、009、020～025、032。公共契约在 core，实现位于 tool，持久审计在 storage。

## 1. 唯一执行边界

- `AegisTool`：提供不可变 ToolSchema 与 `execute(ToolExecutionContext, ValidatedArguments)`；不暴露 Spring ToolCallback 执行入口。
- `ToolRegistry`：按安全精确名称注册、重名拒绝启动；本轮生产仅注册 http_get，测试工具仅通过测试装配出现。
- `ToolExecutor`：接收已验证 session/process/iteration、当前 Profile、原始 ToolCall，返回结构化 ToolObservation；只有该实现可以调用 AegisTool.execute。
- `Sandbox`：校验工具身份、Profile 允许集合、参数、目标及资源预算，产生本次不可变执行许可；许可含固定地址、截止时间及额度，不是“校验过字符串即可任意执行”。
- `ToolInvocationAuditStore`：begin/reject/complete，返回表示独立事务**已提交**的结果；未知/重复完成为错误。tool 不引用 JPA Repository。

执行顺序：整响应重复 ID 检查 → 字段/敏感/大小检查 → 同 process+callId 去重 → 注册/Profile/参数/Sandbox 校验 → begin 提交 → 执行一次 → 安全结果/错误映射 → complete 提交 → Memory 提交观察 → 组闭合后再调用模型。

校验拒绝走持久 reject，零执行。可可靠记录的工具失败/拒绝/超时回填模型，不能外层自动重试。审计不可用或观察保存失败则停止，不能继续下一工具或 Provider。完成审计失败保持结果未知；不能执行同 callId 补救。具体状态字段见 [数据模型](../data-model.md)。

## 2. 观察格式

结构化观察至少包含 `callId/name/success/errorCode/externalOutcome/data`。成功 data 为受限工具结果；失败 data 为空，errorCode 来自固定枚举（UNKNOWN_TOOL、TOOL_NOT_ALLOWED、INVALID_ARGUMENTS、SANDBOX_DENIED、TOOL_TIMEOUT、TOOL_RESPONSE_LIMIT、TOOL_EXECUTION_FAILED 等）。禁止原始异常、认证头、完整 URL 或自由错误正文进入错误说明。

同 ID、同工具名、同规范参数且已有可靠观察，复用原观察内容为当前组产生对应 TOOL 消息，不再执行；冲突结束。调用结果不确定且无可靠观察时不得复用为成功。同一响应重复 ID 的检测必须在其中任何工具执行之前完成。

工具返回中含已知密钥时，审计记录安全失败说明与真实外部 outcome，Agent 以 SENSITIVE_CONTENT 结束，不把结果保存或回填模型。不能将已发 GET 描述成 NOT_SENT。

## 3. http_get schema

```json
{
  "type": "object",
  "properties": {
    "url": {"type": "string", "description": "经部署授权的天气查询绝对地址"}
  },
  "required": ["url"],
  "additionalProperties": false
}
```

ToolSchema 名称为 `http_get`，description 非空。只用现有 Provider 支持的 schema 子集，不新增 format/maxLength 等不支持语义。执行端严格 JSON 解析：对象、仅 url、值非空字符串、无重复键、无尾随 JSON、深度≤8、参数 UTF-8≤配置值。URL 最长2048字节；不允许模型提供 headers/body/credentials/method/proxy。

## 4. 网络许可

1. 只接受绝对 HTTPS URI；HTTP 仅在隔离测试显式 loopback 许可中允许。拒绝 userinfo、fragment、非法端口、非规范/IP混淆表示、反斜线、路径穿越/编码分隔符；IDN 域名按标准 ASCII 规范化后 exact 比较，不用 suffix/contains 匹配。
2. 部署授权必须匹配 scheme、规范 host、port、GET、批准 path、查询键集合；查询值只能是有界无敏感值的数据。不依赖提示词里的“请访问安全网址”。默认 targets=[]，任何目标均拒绝。
3. 每次解析一次域名，验证全部 DNS 结果；出现任意私网/loopback/link-local/metadata/multicast/保留地址即拒绝，不只挑看起来安全的一个。识别 IPv4-mapped IPv6 与转换地址，无法安全分类则拒绝。
4. 从全部合格公网地址中确定选择一个，固定到本次独立 HttpClient5 DnsResolver；之后不再查询系统 DNS、不切换备用地址。原域名保留于 URI/Host/SNI，系统证书信任及 hostname 验证保持启用。
5. 禁自动重试、重定向、代理发现、cookie、认证协商缓存、压缩协商与跨调用连接复用。3xx 不跟随，4xx/5xx 为固定工具失败，不重发。TLS/连接失败也不切 IP。
6. DNS 2s、connect 3s、read 5s、总执行10s为默认值，部署可缩小；允许范围及关系在启动校验。DNS 工作使用有界专用池（最多4个任务、无无界排队），超时结果不得触发后续连接。HTTP deadline 到达时主动取消请求并关闭资源，而不是只停止等 Future。
7. 响应在流中最多读配置上限+1，超限立即失败；不接受压缩编码、非 UTF-8 或二进制正文，不把超长结果截短成正常天气。成功只返回 statusCode、mediaType、受限 body；不把响应认证头/完整 URL回填模型。

全局最多8个并发工具执行许可，超出立即形成受审计的资源拒绝；许可在超时、异常、取消后释放。白名单/地址固定化是应用层控制，不提供容器级、OS级或多租户强隔离。

## 5. 默认与真实测试

ToolExecutorTest、HttpGetToolTest、SandboxPolicyTest、ToolAuditPersistenceTest、ToolDeduplicationTest 使用显式批准的临时回环服务、确定性 DNS/Clock 替身。必须断言实际请求次数：未授权/超限/审计 begin 失败=0；成功=1；503/3xx/超时无自动重发。

额外反例：混合公私网 DNS、DNS rebinding、IPv6/转换地址、未知查询键/非法URI、响应超限、deadline后零新连接、同调用重复/冲突、同响应重复、终态提交失败、资源关闭、敏感结果不保存、生产无测试工具。真天气只在 [独立验收](acceptance.md) 中启用。

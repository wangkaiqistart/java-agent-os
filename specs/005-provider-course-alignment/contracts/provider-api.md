# 调用契约：兼容入口、Profile 与工具说明

**状态**：拟实现 | **归属**：core 契约，provider 实现 | **故事**：A、B、C

## Java 公共入口

```java
// ProviderService：旧入口保持，新重载必须提供会话关联标识。
ModelResponse chat(ModelRequest request) throws ProviderException;
ModelResponse chat(String sessionId, ModelRequest request) throws ProviderException;

// ProfileProviderService：ProfilePrompt 为框架无关领域输入。
ModelResponse chatProfile(String sessionId, String profileName, ProfilePrompt prompt)
    throws ProviderException;
```

ProfilePrompt 只包含 messages、toolSchemas、可空 maxOutputTokens；领域集合不可变。Profile 模型名称和温度由启动快照提供，不接受调用方覆盖；身份 prompt、bootstrap、skills 等元数据不在此入口自动拼接。调用方提供最终消息，本轮不实现 PromptBuilder。

DefaultProviderService 同时实现两接口；boot 仅注册一个具体实现 bean，接口注入都指向它。旧 service 构造器保留并使用空 ProfileRegistry；旧 ModelRequest 五字段、ModelResponse、ProviderFailure 的结果语义保持。新增重载不影响旧调用方源码；不承诺第三方 ProviderService 实现类的二进制兼容。

## 统一执行顺序

1. 入口先生成新的 requestId，确定入口关联模式。旧入口为未关联，新入口为必须关联；null 不能触发兼容回退。
2. 校验 sessionId：只接受规格正则，不 trim/查会话表；非法原值不得放进异常、审计或报告，安全审计关联置 null。
3. Profile 入口校验名称后在不可变索引中查找，转换成原 ModelRequest。未知名称不回显、不补默认，不把未经注册的内容写入身份字段。
4. 复用显式 Provider/模型/可用性与消息/schema 校验。Profile.tools 是 schema 名称允许集合；越界拒绝，未给出的工具不自动补齐或执行。
5. 校验失败且审计可写时插入一条 REJECTED，attempt_no=0；合法 sessionId 继续关联，非法为空。拒绝审计失败返回 AUDIT_UNAVAILABLE/NOT_SENT，不能声称已落库。
6. 有效请求进入唯一循环：begin 提交 → 单次模型调用 → complete 提交 → 仅临时网络/超时在额度内重试。前置提交失败阻止该次发送；终态提交失败 AUDIT_UNAVAILABLE/UNKNOWN 并停止重试。

Profile 的查找/转换必须位于这条边界内，不允许外层门面在生成 requestId/拒绝审计之前抛配置错误。未发送的已校验请求不会产生 STARTED；开始外呼的尝试编号从 1 开始。

## 会话与审计端口兼容

AuditStart、AuditRejection 新增可空 sessionId，并保留旧参数顺序的辅助构造器，旧构造器显式设 null；LlmCallAuditStore 三个方法签名不变。AuditCompletion 不接受 sessionId 或自由错误文本；通过 AuditHandle 更新原行，不可换关联。

合法关联不传入 SDK/模型消息，不新增对 SessionRepository、认证服务或会话表的依赖。两会话及同会话多请求不得互相覆盖；逻辑请求重试仍共用 requestId/sessionId，不同 attemptNo。未知 token 用量继续 null，不推算或填零。

## 错误

| 新增 code | 触发条件 | 外呼 / 重试 |
| --- | --- | --- |
| SESSION_REQUIRED | 新入口 sessionId 为 null | 0 / 否 |
| INVALID_SESSION_ID | 空串、空白、长度/字符非法 | 0 / 否 |
| PROFILE_REQUIRED | Profile 入口名称缺失或空白 | 0 / 否 |
| UNKNOWN_PROFILE | 名称非法、不存在或已隔离 | 0 / 否 |

其余沿用原 ProviderErrorCode。非法/不支持 schema 使用 UNSUPPORTED_TOOL_SCHEMA，Profile 工具越界使用 INVALID_REQUEST。所有 code 有固定安全说明，不能拼入输入值或原始厂商异常；响应和日志不回显 Profile 原始名称/sessionId。

`externalOutcome` 继续按整个请求合并，UNKNOWN 优先于 RESPONDED，全部未外呼才 NOT_SENT。重试默认 0，最多 N+1；SDK maxRetries=0，传输重发/重定向关闭。400/401/403/429/5xx、协议/参数/审计错误均不变成自动重试。超时后重试可能重复计费，不承诺上游幂等。

## JSON Schema 支持子集

支持范围在实现前明确如下；所有校验递归进行，不能只看根节点。参数 JSON 最多 256 KiB、深度 32、节点 4096；拒绝重复 JSON 键、非 JSON 文本及超限。

| 路径/关键字 | 支持内容与约束 |
| --- | --- |
| Tool 名称 | `[A-Za-z0-9_-]{1,64}`，同请求不得重名 |
| Tool 描述 | 字符串完整保留；未提供沿用旧契约转为空字符串，不伪造说明 |
| 根 | 必须 object 类型 |
| type | 单个 object/array/string/number/integer/boolean/null；不支持 type 数组 |
| description | 各层字符串，包含属性/数组项/嵌套对象，完整保留 |
| properties | 对象；属性值为递归支持的 schema，属性名称保持原样 |
| required | 唯一字符串数组，成员必须存在于同层 properties；不改变字段顺序/必填集合含义 |
| items | 数组必须声明单个递归 schema；不支持 tuple/prefixItems |
| enum | 非空、无重复的标量数组；值必须符合所在标量 type；不支持 object/array enum |
| additionalProperties | 仅 boolean，可选；缺省不补值、不改为 false |

object 节点才允许 properties/required/additionalProperties；array 才允许 items，scalar 才允许 enum。未列关键字（含 $ref/$defs/$schema、组合约束、format、pattern、数值/长度约束等）本轮全部安全拒绝，不删除后发送。根及嵌套节点都遵守同一规则；空 properties、空 required 合法，不自动扩充。

映射使用 strict(false)，不补 required 或 additionalProperties；按最终 HTTP `tools[].function` 对比 name、description 与 parameters 的语义树，JSON 属性顺序不作为差异，数组顺序保留。既有工具字段若超出本轮子集，应明确失败，不能扩大宣传为全 JSON Schema 支持。

## 结构化返回及零执行

保留文本、tool call id/name/arguments/顺序，纯 ToolCall 合法；文本和多个意图同时存在时都返回。参数仍须合法 JSON object；空文本且无意图为协议错误。Provider 不执行调用、不请求第二轮、不访问 ToolRegistry/执行器/Sandbox。

仅使用 Spring AI 2.0.1 的直接 OpenAiChatModel.call；禁用 strict 改写，不装配执行 Advisor，描述符 call 永远抛安全禁止异常。没有可从 YAML/请求打开执行的开关，也不编写本版本不存在的 internalToolExecutionEnabled 调用。后续 ReAct 执行工具仍须 AegisTool/Sandbox，本契约不提供替代执行路径。

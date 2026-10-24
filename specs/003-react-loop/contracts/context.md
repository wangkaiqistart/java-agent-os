# 上下文组装、容量与敏感内容契约

**性质**：待实施；依据 FR-011、015～019、027、030～033。[配置键](configuration-cli.md)；[数据模型](../data-model.md)。

## 1. 输入与顺序

PromptBuilder 输入：启动 Profile/AgentSource、MemoryService 视图、ContextLoader、授权且已注册工具 schema、Clock/ZoneId、ContextBudget、SensitiveContentGuard。

系统必需部分严格按以下顺序：`identity.prompt`（可空角色）→ AgentSource 对应 `AGENT.md` → Bootstrap（声明顺序或默认固定顺序）→ 已绑定 Skill 正文（绑定名称稳定排序）→ 当前日期/时间/明确时区（ISO-8601）。之后可加入有界长期记忆片段，再按原顺序加入会话消息。工具说明仅作为 ProfilePrompt.toolSchemas 发送，不包含 Java 执行代码。

无角色设定时省略该片段，不虚构角色；AGENT.md 为必需普通 UTF-8 文件，不能解析其 frontmatter 作为运行配置。正文是数据，不展开环境占位符、不执行脚本。当前时间由可替换 Clock 与 `aegisos.react.zone`（默认 UTC）产生，测试固定时间。

长期记忆通过 MemoryService 得到，默认空；可选片段与必需系统指令单独建模。召回片段必须先做敏感检查：命中即 SENSITIVE_CONTENT 并结束当前处理，不能通过移除后继续来绕过 FR-033；只有通过敏感检查但容量紧张的可选片段才允许整段移除，不静默截断为错误含义。第一条 SYSTEM 的必需指令末尾仍为时间；可选记忆以第二条带明确记忆数据标记的 SYSTEM 消息发送，不伪装成用户真实发言。裁剪该可选消息不删除第一条必需系统指令。

## 2. 文件与来源

- boot 在 Profile 启动加载时创建 AgentSource 索引，profileName 与目录名允许不同；索引只覆盖最终有效注册项。每轮从索引取目录，不能重新扫描/合并 profile.yml。
- AGENT.md 限于启动绑定的 Agent 目录；Bootstrap 引用以 `.aegisos` 为批准根解释，拒绝绝对路径、`..`、越界链接。非空列表为显式引用，缺失/不可读/非法均失败。
- Profile.bootstrap 为空（包括省略）时，尝试 `.aegisos/AGENTS.md`、`.aegisos/SOUL.md`、`.aegisos/USER.md`；仅真正不存在可 WARN，权限、解码、I/O 故障不能伪装为缺失。
- Skill 来自既有相对目录绑定。每次检查绑定仍指向启动快照目标；SKILL.md 必须仍在该目标及公共 `.aegisos/skills` 根内。改链接指向需重启，文件正文改动下次构建生效。
- 读取前验证真实路径/普通文件；以批准目录描述符遍历已解析目标，NOFOLLOW_LINKS 开文件；有界读取后再次核对身份及边界。安全读取能力不可用时固定拒绝，不读取后才发现越界。可信管理员维护工作区，控制不宣称抵御拥有同等 OS 权限的恶意写者。
- 上限同时覆盖文件数、单文件和总上下文；严格 UTF-8，最多读 limit+1，超限不截短；每个必需文件进入后续发送/保存前进行敏感检查。警告仅记录安全来源标识，无正文/绝对个人路径。

### 已确认的平台边界（2026-10-22）

实施时实测本机 macOS/Zulu 21.0.9 返回 `sun.nio.fs.UnixDirectoryStream`，不提供 `SecureDirectoryStream`。用户确认保持上述安全契约：生产正文读取仅支持实际提供该能力的环境；macOS 缺少该能力时明确 `CONTEXT_FILE_UNAVAILABLE`，不采用弱化的路径检查替代。正文读取正向与安全验收在支持该能力的 Linux 环境进行。macOS 测试结果只证明拒绝行为，不能替代 Linux 正向验收；Linux 测试必须显式要求能力存在，不得跳过或将拒绝计为成功读取。

## 3. 确定性容量单位

部署必填 `aegisos.react.max-context-bytes`，范围 4096～1048576。度量不是厂商 Token：将候选逻辑负载规范序列化为 UTF-8 JSON，计算字节数，使用所有字符串的 JSON 转义后长度，中文不能按 String.length 代替。

规范顶层固定键顺序为 `messages`、`toolSchemas`、`maxOutputTokens`；每条消息固定 role/content/toolCallId/toolCalls；每个 call 固定 id/name/argumentsJson；schema 固定 name/description/inputSchemaJson。null 显式为 null、空列表为 []，不输出空格；双引号/反斜线转义，控制字符用小写 `\u00xx`，其余 Unicode 严格 UTF-8；参数/schema 文本作为 JSON 字符串计量。此格式只用于计量/指纹，不替换 SDK 协议序列化，不产生临时含正文文件或日志。

单消息预算覆盖整条规范消息；另外有单文件、工具参数/结果、工具数等限制。固定结构本身计入总量，schema 不是免费上下文。输出 Token 上限明确传给 ProfilePrompt，但不声称字节与 Token 换算固定，模型自身上下文不足仍返回安全 Provider 错误。

裁剪顺序：

1. 读取窗口内最近最多 `max_history_turns` 个 **COMPLETED 用户处理轮次**，每轮含全部模型/工具交换；当前处理的用户请求另行保留，不计入20。
2. 必需系统指令、当前用户请求及授权 schema 单独计算；任何单条/必需合计超限，立即 CONTEXT_LIMIT、零 Provider 调用，不能裁掉 Skill 或用户正文。
3. 加入可选长期记忆和历史/当前闭合组；超限先整段移除可选长期记忆，再从最旧完成历史轮次开始整轮删除，最后从当前处理中最旧 CLOSED 工具交换组开始整组移除。当前 USER 与最近可容纳组保持原顺序。
4. OPEN 工具组永不进入发送视图；历史裁剪不可留下只有工具结果或只有待执行意图。记录移除轮次/组数及字节变化元数据，不改变持久状态，不触发总结模型。

## 4. 工具 ID 投影

每个工具交换组中第 k 次调用，发送 ID 为 `t_<32位processUUID>_<responseOrdinal>_<callOrdinal>`。ASSISTANT/TOOL 同组引用同步替换，顺序和观察正文不变；每次构建从持久位置推导，跨构建稳定且整份 Prompt 唯一。

原始 callId、原始参数和去重身份仍保留在受保护消息/调用记录中，wire ID 不进入执行去重键。同原始 ID 的复用发生在不同组时可有不同 wire ID；必须测试重复复用后仍被原 Provider mapper 接受。

## 5. 敏感内容拒绝

SensitiveContentGuard 仅有 `check(content)`/结构化检查能力，不返回密钥。boot 复用启动 Provider 凭据解析值建立内存快照，不扫描整个环境、不持久化或输出清单。原始字符串与解析后字符串值均检查，防止仅查 JSON 源文遗漏 `\u` 转义值；不宣称可检测任意编码或未知密钥。

检查顺序：CLI 用户正文/身份 → 文件与 Profile 将发送的片段 → Memory 恢复内容 → 最终 Prompt → 模型所有不可信文本/调用字段 → 工具参数解析值 → 工具结果 → 最终输出。每个相应持久化/转发/回显边界均有 guard，不能先写后删。

命中已知完整凭据或明确敏感载体（带值 Authorization Bearer、私钥 PEM、结构化 api_key/password/token 等非空实际值）即固定 SENSITIVE_CONTENT 并停止。安全引用 `credential-ref: env:NAME`、占位说明和讨论 API key 的普通句子允许。敏感识别规则、占位符白名单须用正反例固定，不能以泛化“包含 key 就拒绝”实现。

使用合成高辨识假密钥验证用户、AGENT/Bootstrap/Skill、历史、模型文本/工具字段/转义参数、天气响应各入口；断言后续发送/保存/stdout/stderr/审计/日志没有假密钥，只有固定错误。已发生的外呼不能撤销：Provider/工具审计如实保留元数据，不能为了零泄漏断言删除记录或改成未发送。

## 6. 计划测试

PromptBuilderTest、ContextLoaderTest、SensitiveContentGuardTest、PromptProjectionContractTest、ProfileSourceMappingTest：固定时钟与顺序、名称不等于目录、文件每次变化、缺失/WARN、路径替换与绑定快照、UTF-8/JSON转义边界、单消息/总量超限、20轮窗口、配对裁剪、持久历史不删、已知秘密拒绝与正常描述不误拒绝。默认测试不读取个人工作区/环境秘密。

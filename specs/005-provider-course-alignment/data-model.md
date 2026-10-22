# 数据模型：Profile 与会话审计

本文为拟实现模型，公共类型位于 core，不依赖 Spring AI、SnakeYAML 或 JPA。类型名称为计划名称；字段来源和错误规则见 [配置契约](contracts/profile-config.md)及[调用契约](contracts/provider-api.md)。

## Profile

Profile 为启动快照，按 `name` 精确、区分大小写查找。全部集合深度复制并冻结；包括嵌套记录在内的 toString 不输出字段内容。可读字段不是秘密容器，不得保存凭据。

| 字段 | 领域类型 / 来源 | 校验与缺省 |
| --- | --- | --- |
| name | String / profile.yml | 必填；`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`；不 trim；可与目录名不同，以便检测跨目录重名 |
| description | 可空 String / YAML | 可省略；最多 4096 字符；不展开环境变量 |
| identity | 可空 Identity / YAML | agent_name、prompt 可选字符串；prompt 最多 65536 字符；仅保留元数据，不读取其引用文件或拼接模型消息 |
| provider | 必填 ProviderSelection / YAML | name/model 明确，按现有安全标识规则及全局允许集合校验；temperature 可空、有限数值 [0,2] |
| tools | List<String> / YAML | 可省略为空；唯一 Tool 名称，遵守现有 ToolSchema 名称格式；只限制可传递的 schema 名称，不执行工具 |
| skills | List<SkillBinding> / Agent skills/ | 仅合法相对软连接派生，按本地绑定名排序；YAML 出现 skills 即配置错误 |
| mcp_servers | List<String> / YAML | 可省略为空；唯一、安全名称引用；不连接 MCP，不查询注册表 |
| channels | List<ChannelMetadata> / YAML | name 必填且唯一；config 为受限不可变元数据映射，可省略为空；不建立 Channel |
| notify_channels | List<String> / YAML | 可省略为空；唯一通知名称引用；不解析数据库注册表、不保存 URL/凭据 |
| schedules | List<ScheduleMetadata> / YAML | key、cron、zone、message 必填；display_name 可选；key 唯一，zone 可由 ZoneId 解析，cron/message 非空；仅结构校验，不注册调度 |
| bootstrap | List<String> / YAML | 可省略为空；只保存相对文件名；拒绝绝对路径及 .. 片段；不读取/执行文件 |
| settings | Settings / YAML | max_iterations、max_history_turns 可空正整数；其余受限元数据保留；不在本轮赋予 ReAct 行为 |

顶层只允许上表除 skills 外的 YAML 键。identity/provider/schedule 固定字段不接受未知键。channels.config 与 settings 扩展部分允许字符串键、字符串/有限数值/布尔/null、列表、映射组成的有界 MetadataValue 树，拒绝对象、日期、二进制等类型。每列表最多 256 项，总节点最多 4096，深度最多 32。所有字符串及节点仍受单文件大小上限约束。

扩展容器也执行敏感字段策略：规范化后的 api_key/api-key、token、password、secret、authorization、credential、webhook/url/连接串类字段不得承载值；本轮不支持把连接配置藏在 channels.config 中。诊断只显示固定类别，不回显原始键或值。合法非敏感元数据完整保留，不能静默删字段后算加载成功。

## SkillBinding 与路径关系

SkillBinding 保存安全本地绑定名、相对于工作区的绑定路径及目标路径，供后续能力消费；本轮不读取 SKILL.md 正文，也不执行任何文件。加载时对工作区根、Agent 根、Agent 目录、profile.yml、skills 容器和公共 skills 根验证 realpath 边界。skills 内每项必须为指向公共 `.aegisos/skills/` 下目录的相对软连接，拒绝绝对、悬空、循环、越界及普通文件。不得把软连接规则称为多租户隔离。

## ProfileLoadReport / ProfileRegistry

- ProfileLoadReport：有效 Profile 集合与固定代码的诊断集合；来源仅安全相对路径，非法路径名替换为固定占位，行/列可选。不携带 Throwable/解析树/环境值。
- 先解析全部候选，再按 name 分组；同名候选全部无效，无冲突的合法 Profile 继续注册。已能安全取得名称但其他字段非法的候选也参与重名冲突排查；完全无法解析名称的文件只报其自身错误。
- ProfileRegistry：不可变 Map，get/find 只读，不提供运行时注册/删除。目录缺失为空索引；遍历根不可读时报告安全错误且不扫描其他目录；根边界本身不可信/越界则拒绝建立加载器。
- Provider 存在且模型获准但凭据暂不可用，Profile 元数据可注册；调用时返回既有 PROVIDER_UNAVAILABLE。未知 Provider 或不获准模型不注册。

生命周期：未加载 → 启动校验 → 有效快照或隔离错误；进程运行期间不再变化，重启才生成新快照。修改环境也不改变当前请求及重试对象。

## ProfilePrompt 与模型请求

ProfilePrompt 为不可变的 messages、toolSchemas、可空 maxOutputTokens。由 Profile 明确填入 provider/model/temperature，再进入现有 ModelRequest；不新增同名覆盖字段，不从 tools 元数据生成可执行工具。传入的 schema 名称必须是 Profile.tools 的子集，未提供的 schema 不自动补齐；未知 ToolRegistry 不在本轮依赖内。

旧 ModelRequest 保持原五字段：providerName、modelName、messages、toolSchemas、generationOptions。ModelResponse、ProviderFailure 的 requestId/结果语义保持；不需要向响应新增 sessionId，调用方按 requestId 查询审计。

## 会话关联

| 项目 | 规则 |
| --- | --- |
| sessionId | 1～64 个 ASCII 字符；`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`；区分大小写，不 trim |
| 新会话入口 | 缺失为 SESSION_REQUIRED，空白/格式非法为 INVALID_SESSION_ID；不保存或回显原值 |
| 旧调用入口 | 显式兼容模式，session_id=null，不伪造标识 |
| 格式合法但无会话记录 | 接受为追踪值，不查表，不视为已认证或会话已存在 |
| requestId | 每个逻辑请求服务端新 UUID，包括本地拒绝 |
| attemptNo | 外呼从 1 递增；本地拒绝为 0；同请求重试保持 sessionId/requestId 不变 |

内部显式区分 LEGACY_UNASSOCIATED 与 SESSION_REQUIRED 两种模式，不能用 sessionId 是否为 null 猜测入口。先校验身份，合法 sessionId 可用于其他校验失败的拒绝记录；无效值一律入库 null。Profile 名及未知 Provider/模型原值不增加到审计字段中。

调用方只能传非敏感追踪标识。格式校验不是通用敏感信息识别或鉴权；日志和异常不输出传入身份，验证通过后仅在受控审计字段中保存合法关联值。

## llm_calls V2

保留 V1 全部字段及语义，新增：

| 字段 | SQLite / Java | 约束 |
| --- | --- | --- |
| session_id | TEXT / 可空 String | 新会话合法值，旧入口/历史/无效身份拒绝为 null；不建会话 FK |
| success | BOOLEAN / Boolean | 仅 null/0/1，必须与 status 一致；不能用 primitive boolean |
| error_message | TEXT / 可空 String | 最多 256 字符；固定安全说明，不来自 Throwable 或厂商正文 |

| status | attempt_no | success | error_code | error_message | 时间/用量 |
| --- | --- | --- | --- | --- | --- |
| STARTED | >0 | null | null | null | 原开始时间；终止时间/耗时为空 |
| SUCCEEDED | >0 | true | null | null | 终止时间/耗时非空；用量未知保持 null |
| FAILED | >0 | false | 非空 | 非空安全说明 | 终止时间/耗时非空；沿用既有失败用量规则 |
| REJECTED | 0 | false | 非空 | 非空安全说明 | 终止时间非空、耗时 0、不可重试 |

状态仅允许 STARTED→SUCCEEDED/FAILED，REJECTED 直接插入；不能把已有终态再次完成。complete 通过 AuditHandle 定位，sessionId 不可变。UNIQUE(request_id,attempt_no) 保留，新增 `(session_id, created_at)` 非唯一索引。DB 对状态/null/取值/标识长度建立明确 CHECK，Java 与 SQL 都校验；SQLite NULL 逻辑须有反例测试。

安全说明集中由稳定 ProviderErrorCode 映射，例如“模型调用超时”“认证失败”“请求配置无效”；审计端口不接收自由格式错误文本。迁移无法识别的历史错误代码保留原值，新增说明使用“历史调用未成功，详细原因不可用”。

## 数据库生命周期

| 当前状态 | 行为 |
| --- | --- |
| 真正空库 | 事务内创建 V2 完整表、索引及版本记录；不调用旧 V001 再自动升级 |
| 合法 V2 | 校验表/字段/约束/索引后开放服务；缺失或损坏拒绝 |
| 合法 V1 | 拒绝当前版本启动，提示停机备份后手工迁移；不修改原库 |
| 有应用对象但无版本、未知版本、形状不匹配 | 拒绝，不清表/猜测/降级 |
| 手工迁移进行中 | 停机、备份、BEGIN IMMEDIATE；校验/重建/复制/检查/最后更新版本；任一失败回滚 |
| 迁移完成 | V2；重开可查询，新调用按新字段写入；再次迁移以明确 ALREADY_MIGRATED 非零退出，无修改/重复复制 |

原有字段必须逐行等值保留，历史新增 session_id 全部为 null。回滚使用升级前一致性备份及旧应用，不能只把 version 改回 1。详细步骤见 [审计契约](contracts/audit-storage.md)。

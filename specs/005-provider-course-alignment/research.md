# 研究记录：Provider 课程对齐

日期：2026-10-22。依据为已澄清 [规格](spec.md)、当前 POM/源码、本机依赖 API 检查、只读公开文档。Profile、审计、测试工具链三个研究方向分别核对后合并；未读取凭据、执行真实模型请求或构建。

## R1：Profile 来源与模块

**决定**：boot 从 Agent 内 profile.yml 加载文件、环境与软连接；core 定义不可变 Profile、嵌套类型、只读索引；provider 消费索引。AGENT.md 仅为未来任务指令，不解析 frontmatter，不组装提示词。

**原因**：当前没有 Profile/AgentLoader 实现，在九模块中增量补齐即可；领域不依赖 Spring/JPA/YAML。索引构造后冻结，不公开 register/remove；重名项全部排除，不由扫描顺序选中。

**比较方案**：独立 profiles 目录、frontmatter 派生、热更新已由 clarify 排除；新增模块或由 provider 读盘会扩大耦合。

**证据**：根 POM/宪法；boot 的 ProviderConfiguration；课程第 3～4 页。长期方案中的 frontmatter/热更新由本功能批准决定覆盖，实施时同步说明。

## R2：安全 YAML 与快照

**决定**：boot 显式声明 BOM 管理的 SnakeYAML 2.6，SafeConstructor 配合受限事件/节点解析、字段白名单及深度不可变集合。限制见 [配置契约](contracts/profile-config.md)。

**原因**：本机 Boot 4.1.1 BOM 管理 2.6；javap 验证 LoaderOptions 的重复/递归 key、alias、深度、codepoint、TagInspector 及 SafeConstructor API。仅 maxAliasesForCollections=0 不能拒绝全部 anchor/alias，必须在事件/节点层拒绝；SafeConstructor 仍支持 timestamp/binary/set，也必须筛掉。

**比较方案**：默认 Yaml.load/任意对象绑定扩大攻击面；新配置框架没有必要；递归展开所有字符串可能泄漏凭据，仅支持三个 provider 字段全值占位。

**诊断**：只输出固定错误代码、安全相对来源与可选行列号，不透传解析异常 message/cause、原始键值/解析树。Profile 及嵌套对象 toString 脱敏。

**证据**：本机 ~/.m2/repository/org/yaml/snakeyaml/2.6/、Boot BOM；现 EnvironmentCredentialResolver/ProviderProperties。API 可用不表示加载器已实现。

## R3：接口与唯一审计边界

**决定**：旧 ProviderService.chat(ModelRequest) 保留；新增带 sessionId 重载。另设 core 的 ProfileProviderService.chatProfile(sessionId, profileName, ProfilePrompt)，DefaultProviderService 实现两接口，boot 暴露同一具体 bean。旧构造器委托空 ProfileRegistry，新增构造器注入索引。

**原因**：共用现有 begin/call/complete/retry。ProfilePrompt 仅 core 消息/schema/可选 maxOutputTokens，无 provider/model/temperature 覆盖。生成 requestId 后才查 Profile，失败也要 REJECTED。ModelRequest 五字段不变；旧 AuditStart/AuditRejection 构造器将 sessionId 设 null。

**比较方案**：直接加 ModelRequest 主参数破坏现有调用；在服务外校验 Profile 绕过拒绝审计；core 接受 Spring AI Prompt 违反边界。兼容现有调用方，不承诺第三方接口实现类二进制兼容；测试替身需适配新增重载。

**会话**：按规格正则，不 trim、不查询会话、不建立 FK。旧入口明确未关联；新入口 null 不得退回旧模式。合法标识只用于审计，不传模型消息/SDK，也不能作鉴权。

**证据**：DefaultProviderService 的 UUID/拒绝审计/循环；core ModelRequest、AuditStart、AuditRejection；AuditMetadataPolicy 只保存已注册身份。原 US-1 的启动/有限重试/显式模型/审计门槛与源码核对一致。

## R4：审计字段与迁移

**决定**：以 status 为权威，增加 nullable session_id、Boolean success、安全 error_message。complete 同一条件更新维护全部终态字段。新空库初始化 V2；V1 必须离线手工升级；V001 保持原文。

**原因**：当前初始化器只支持版本 1，JPA 不自动建表。必须验证真实新库/旧库/重开/约束，不能只改测试 SQL。SQLite CHECK 遇 NULL 可通过，约束必须显式写 IS NULL/IS NOT NULL；迁移重建表统一约束，version 最后更新并与复制同事务。

**比较方案**：Hibernate update、启动 ALTER、覆盖 V001 不满足要求；SQL view 不满足物理列；只留布尔值会丢失 STARTED/REJECTED；不引入 Flyway。

**历史**：原字段逐行保留，包括主键、请求/尝试、时间、64 位用量、错误代码；新 session_id 一律 null；STARTED 保持未知；未知历史错误只生成通用安全说明。不补历史身份，不完成/重放旧请求。

**证据**：storage 的 AuditSchemaInitializer、JpaLlmCallAuditStore、LlmCallRepository、V001__llm_calls.sql。[审计契约](contracts/audit-storage.md)定义脚本边界。

## R5：schema 完整性及零执行

**决定**：公开 JSON Schema 子集并递归校验，参数 JSON 语义树不变，显式 strict(false)，验证最终 HTTP JSON。直接 OpenAiChatModel.call，不使用 ChatClient Advisor/工具执行器；SchemaDescriptor.call 继续立即拒绝。

**原因**：现测试主要检查 tools 数量，现校验主要在顶层，不能证明嵌套完整。Spring AI 2.0.1 本机 API/字节码显示模型解析工具定义但不执行；没有 1.x 的 internalToolExecutionEnabled(false)，不能照搬旧开关。strict 可改写 required/additionalProperties，必须关闭并测线上的 JSON。

**比较方案**：仅 getter 测试不能覆盖序列化；声称支持全部 schema 却丢约束不可接受；引入真工具会越过本轮 Sandbox 范围。

**证据**：OpenAiMessageMapper、OpenAiCompatibleAdapterTest；本机 spring-ai-openai/model 2.0.1 的 OpenAiChatOptions、ToolCallingChatOptions、OpenAiChatModel。

## R6：冒烟发现与结果

**决定**：仅 boot 增 real-provider-smoke Maven profile，Failsafe 3.5.2 绑定 integration-test/verify，包含 ProviderSmokeIT 和 real-provider 标签；零发现/指定测试缺失失败。默认 Surefire 不发现 IT，CI 不启用。缺配置不用 Assumptions；独立核验器输出 PASS/FAIL/NOT_RUN，后两者均非成功退出。

**原因**：Surefire 默认不含 *IT；课程 test -Dgroups=integration 不能保证执行。门槛只设 boot，避免 reactor 无测试模块被误伤。检查本轮报告、执行数/零跳过、物理发送计数及重开审计；不能只看审计条数或旧报告。

**比较方案**：Surefire 强行指定 IT 可行但排除/启用容易混淆；全局 failIfNoSpecifiedTests=false 会掩盖缺失冒烟类。使用 BootProviderFixture 的最小生产装配，不启动 Web。

**证据**：根 POM Surefire 3.5.2、boot 当前无 Failsafe；本地 Surefire 插件描述符；默认 engineering.yml。Failsafe 尚未交付/下载验证，实施必须验证解析、发现和失败路径。

## R7：DeepSeek 外部条件及成本

**决定**：遵照 AGENT.md 使用 DeepSeek；显式 deepseek-main / deepseek-flash、env:DEEPSEEK_API_KEY、max-retries=0。Profile 不填 temperature，ProfilePrompt 不填 maxOutputTokens，不发送未确认支持的 max_completion_tokens。固定非敏感短提示、无工具。保留当前根地址追加 /v1 的既有行为；真实单次失败记 FAIL，禁止自动换路径/模型。

**公开证据**：2026-10-22 只读 GET 核对 [首页](https://api-docs.deepseek.com/)、[中文首页](https://api-docs.deepseek.com/zh-cn/)、[模型/定价](https://api-docs.deepseek.com/quick_start/pricing)、[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion)。当日列 deepseek-flash、deepseek-v4-pro、https://api.deepseek.com、/chat/completions。deepseek-chat 是仓内旧示例，不能说已验证当前可用。

**限制**：页面未证明 /v1 仍兼容，也未列 max_completion_tokens，仅列 max_tokens；现工厂追加 /v1，mapper 将 maxOutputTokens 映射 max_completion_tokens。本计划不把 GET 文档当作调通，不静默改变全部厂商协议。若需要路径/参数适配，先记录失败并走显式契约变更，再单独授权真实重跑。

省略输出上限不是低成本保证；当日文档默认 thinking 开启，输出上限可达 64K tokens。短提示、60 秒客户端超时都不是计费 token 上限。真实执行前展示此边界并确认实际调用授权；本轮不真实调用。

## 研究结论

内部技术决定已闭合，无未解决设计占位，可以生成 tasks。厂商兼容/连通、Failsafe 实际解析、迁移正确性和本轮安全门禁仍需实施后证明。原 US-1 的 51 项任务、91 个测试及 NVD 通过只作为历史引用。

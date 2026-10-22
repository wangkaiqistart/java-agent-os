# US-1 技术研究

**日期**：2026-10-20 | **输入**：[spec.md](spec.md)、宪法 1.0.1、当前 POM 与设计文档。

客户端研究与审计边界研究分别完成后合并。以下均为实施决策，尚未修改依赖或验证完整应用。

## 2026-10-21 实施核实结果

本文后续各节保留计划阶段研究。实施已通过 Spring AI 2.0.1 / OpenAI Java core 4.49.0 / Boot 4.1.1 / Hibernate 7.4.5.Final / SQLite JDBC 3.53.4.0 的本地编译和行为测试，证据见 [validation.md](validation.md)。真实厂商调用仍未执行。

AI 2 的 ChatModel 同步路径不执行 Tool；不存在旧版 internalToolExecutionEnabled 开关。本项目不使用 ChatClient/Advisor，只传不可执行的 schema 描述。Spring AI 默认 HTTP 封装会启用连接重发，故明确注入 SDK ClientOptions（maxRetries=0、logLevel=OFF）与自有单次 HTTP 传输层（关闭连接重发/重定向、正数超时、无正文观测）。用量在内存按原生 nullable long 提取，避免 AI 2 的 int 转换或缺字段校验改变语义。未引入原生 Alibaba/DashScope 依赖。

## R1：兼容版本基线

**2026-10-21 更新**：用户批准为修复 Framework 阻断漏洞迁移 Boot 4。当前工程基线为 Boot 4.1.1 / Framework 7.0.9 / Jackson 3.1.5。原 Boot 3.5.10 + Spring AI 1.1.2 组合不再作为实施目标。

**候选**：首批仍使用 `org.springframework.ai:spring-ai-openai` 对接 DeepSeek/Kimi，候选 Spring AI 2.0.1。已核实发布 POM：该 model 使用 Framework 7.0.9、Micrometer 1.17.1、`openai-java-core` 4.49.0。尚未引入 AI 依赖，不能将元数据对齐视为 Provider 集成验证通过。

**Alibaba 边界**：公开发布的 2.0.0-M1.1 为里程碑版；starter 使用 Boot 4.0.0，model 使用 AI 2.0.0-M1 / Framework 7.0.1。首批无原生 DashScope 协议需求，不引入该组合。后续需原生能力时重新选择、收敛并验证，不回用 1.x 版本。

**存储候选**：Boot 4.1.1 BOM 管理 Hibernate 7.4.5.Final，community dialect 必须与 core 同版；SQLite JDBC 3.53.4.0 为原候选，实施前核对可用性与安全性。尚未引入数据库依赖，真实 SQLite/JPA 行为待实施测试。

**验证限制**：AI 2 的客户端、重试与工具 API 需重新读取源码及通过 HTTP stub 证明；原 1.1.2 源码结论仅可作为历史参考。

## R2：显式构造与纯 core 契约

**决策**：每个配置项按 adapter 显式创建 `ChatModel`，以名称登记不可变映射。同一种 OpenAI 兼容协议允许多个实例；不用 starter 推断默认 Provider、不扫描同类型 Bean。core 仅暴露 AegisOS DTO。

**理由**：Provider 身份不能由协议类型决定；请求模型必须显式且在允许集合中。未来 Profile 可填入请求字段，US-1 不负责加载 Profile。

**备选**：暴露 `ChatClient` 耦合外部框架；按 Bean 类型/顺序路由违反显式选择。

## R3：关闭自动工具与底层重试

**决策**：AegisOS 外层独占重试；所有 SDK 与传输层内部重试必须关闭，自动工具执行必须关闭。只传 Tool schema，不注册可执行回调；结构化调用返回调用方。每次外呼均先提交独立审计，配置 `maxRetries=N` 时真实 HTTP 尝试不得超过 `N+1`。

**迁移待验证**：旧 AI 1.1.2 的 `internalToolExecutionEnabled(false)` 与 `RetryTemplate.maxAttempts(1)` 是历史 API 方案。AI 2.0.1 OpenAI model 已改用官方 OpenAI Java 客户端，不能沿用旧 builder 配置并假定生效。T001/T002/T022 实施前须读取候选源码，确定工具开关、SDK 重试、OkHttp 重试和超时的实际配置 API，再用本地 HTTP stub 对失败与 Tool 响应验证零隐藏重试/零 Tool 执行。

**不变约束**：业务默认零重试，仅临时网络与超时符合用户允许的重试；不能用 SDK 的默认错误分类代替 AegisOS 契约，也不能以无法配置为由放宽审计门槛。

## R4：SQLite/JPA 与独立短事务

**决策**：沿用技术方案 SQLite + Spring Data JPA；core 审计端口、storage JPA 实现、boot 注入。begin 独立事务提交后才返回，外呼无事务，complete 另一短事务。通过独立 Spring Bean 调用事务方法，避免自调用失效。禁止用 `ddl-auto=update` 迁移；使用版本化 SQL 初始化资源和 schema version 校验，初始化失败则存储不可用。

**理由**：JPA `save`/`flush` 不等于提交；另一连接可见 STARTED 才能证明前置门槛。SQLite 锁不应跨外呼持有。本次不顺带实施后期双数据库/Flyway 演进。

**备选**：JDBC 对单表较简单，但偏离既有 JPA 选型；内存/日志不满足持久化；跨网络事务不能撤回外部请求。

## R5：审计故障与未知外部结果

**决策**：前置写失败，该次外呼数零；终态提交失败保留 STARTED，返回 `AUDIT_UNAVAILABLE`/`externalOutcome=UNKNOWN`，不返回普通成功，不再自动重试。重启不重放 STARTED。审计正常时每次尝试一行，开始/完成更新同一行。

**理由**：外部模型无法被数据库回滚；失败后再发可能重复计费。同一存储不可写时，也无法要求把拒绝结果写入其中。

**备选**：仍返回成功掩盖审计故障；将 STARTED 认定失败并重放可能重复外呼；备用日志不能冒充持久审计。

**来源边界**：前置失败关闭是用户确认；终态失败处理是本次技术设计补充。spec 已注明这一差别，限定 SC-003/009 适用条件，不新增虚构的用户澄清。

## R6：启动快照与安全元数据

**决策**：启动时解析 Provider 配置和环境变量引用，凭证及连接参数修改均需重启。名称区分大小写；凭证只进入内存客户端，不进入可打印配置 DTO。审计/日志/异常只允许安全元数据，不保存消息、响应正文、Tool 参数、完整端点或原始异常串。禁用 SDK HTTP wire logging 与请求/响应正文观测。

**理由**：避免混合配置和正文里的敏感值泄漏；缺用量为 null，不伪造零。

**备选**：每轮重载违反已确认 Provider 契约；Agent 指令热加载不代表 Provider 连接配置热加载。

## R7：九模块和独立验收

**决策**：以宪法与当前 reactor 九模块为准，影响 core/provider/storage/boot 和根依赖管理。审计在 US-1 完成；测试使用本地 HTTP stub/SQLite/最小上下文，不依赖其他业务模块或真实凭证。

**理由**：docs 的十四模块包含其他演进，AGENT.md 已同步当前基线；不得以此扩模块或推迟审计。

**备选**：真实厂商作为 CI 前提引入费用和非确定性；等 ReAct Demo 才验收 Provider 不满足独立故事测试。

## 历史研究来源（1.x，不作为当前兼容性证明）

- [Spring AI 支持的 Boot 版本](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html)
- [Alibaba starter 发布 POM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-starter-dashscope/1.1.2.4-security-fix/spring-ai-alibaba-starter-dashscope-1.1.2.4-security-fix.pom)
- [Alibaba model 发布 POM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-dashscope/1.1.2.4-security-fix/spring-ai-alibaba-dashscope-1.1.2.4-security-fix.pom)
- [OpenAI model 1.1.2 源码包](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/1.1.2/spring-ai-openai-1.1.2-sources.jar)
- [AI retry 1.1.2 源码包](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-retry/1.1.2/spring-ai-retry-1.1.2-sources.jar)
- [DashScope 源码包](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-dashscope/1.1.2.4-security-fix/spring-ai-alibaba-dashscope-1.1.2.4-security-fix-sources.jar)
- [Boot 3.5.10 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.10/spring-boot-dependencies-3.5.10.pom)
- [Hibernate SQLite 方言所属包](https://repo.maven.apache.org/maven2/org/hibernate/orm/hibernate-community-dialects/6.6.41.Final/hibernate-community-dialects-6.6.41.Final.pom)
- [SQLite JDBC 发布元数据](https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/maven-metadata.xml)

## Boot 4 迁移核对来源

- [Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)
- [Spring AI OpenAI 2.0.1 POM](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/2.0.1/spring-ai-openai-2.0.1.pom)
- [Alibaba 2.0.0-M1.1 model POM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-dashscope/2.0.0-M1.1/spring-ai-alibaba-dashscope-2.0.0-M1.1.pom)

真实厂商可用性、AI 2 SDK 配置、SQLite/JPA 组合编译与 HTTP 传输计数仍属于 US-1 实施验证，不记为本轮已通过。

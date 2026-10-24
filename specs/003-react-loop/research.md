# US2 技术研究与决策

**日期**：2026-10-22　**范围**：[spec.md](spec.md)；全部决定用于后续实施，当前没有新增 Java 能力。

## 研究输入与结论

已读取宪法、当前根及九模块 POM、US2 规格与课程追溯，以及现有 Profile、Provider、SQLite 审计、Boot 装配和冒烟实现。按 `speckit-plan` 要求，三个只读研究子代理分别核对存储/关联、上下文/工具安全、CLI/验收。以下技术未决项均已形成决定；用户已确认的业务语义不再重新选择。

当前事实：Java 21、Boot 4.1.1、Spring AI 2.0.1；`core` 没有 Spring/JPA 依赖；Memory/Tool/CLI 尚为骨架。现有数据库通过 **`aegisos_schema(id=1,version=2)`** 标识版本，不使用 `PRAGMA user_version`。现有 `DefaultProviderService` 是唯一审计/有限重试边界，`JpaLlmCallAuditStore` 的 `begin/complete` 均在独立事务提交后返回。

## R01：模块与公共契约

- **决定**：AgentService、ReActLoop、PromptBuilder、ContextLoader 置于 `core`，仅使用 JDK 和 core 公共端口。MemoryService、SessionStore、LongTermMemoryStore、ToolExecutor、AegisTool、Sandbox、ToolInvocationAuditStore 等契约也在 core；memory 实现门面，tool 实现工具执行/沙箱/http_get，storage 实现持久化，boot 显式接线。
- **理由**：保留九模块与稳定依赖方向；循环不依赖具体 Provider、Memory 后端、HTTP 或 JPA 实现。
- **比较方案**：core 直接依赖 memory 会成环；tool 直接引用 storage 的 Repository 会绕过公共契约。均不采用。

## R02：持久化与并发

- **决定**：运行时生成 UUID 会话与处理身份；会话绑定精确 Profile 名。短事务提交用户输入、模型响应、工具观察和终态。MemoryService 是循环访问会话/长期记忆的唯一门面，长期记忆本轮默认空实现。
- **决定**：一个规范化数据库路径仅允许一个运行进程持有独占锁文件；进程内会话 try-lock 与数据库 RUNNING 部分唯一索引共同阻止并发处理。同数据库不支持跨节点写入，不引入租约/心跳。
- **理由**：跨重启恢复必须依赖已提交状态；仅方法末尾 save 无法满足异常恢复。独占进程锁避免新进程把仍运行的处理误判为中断。
- **比较方案**：纯内存会话违背澄清；仅 synchronized 无法防第二进程；分布式锁超出 US2 范围。

## R03：中断与不确定结果

- **决定**：处理状态为 RUNNING、COMPLETED、MAX_ITERATIONS、FAILED、INTERRUPTED。启动持锁并通过 schema 检查后，将遗留 RUNNING 标为 INTERRUPTED；未闭合工具交换组排除出新 Prompt，保留已提交记录，不自动续跑或重放。
- **决定**：工具开始审计提交后才执行；终态审计提交失败时原行保持 STARTED，处理返回固定不确定错误并停止，不能伪造审计完成。恢复可在独立恢复记录标注未知，不篡改已发生事实。
- **理由**：超时、进程退出、提交失败都不能证明外部动作没有发生。
- **比较方案**：把所有遗留工具自动重试、猜成失败或删除记录均不采用。

## R04：Provider 处理关联与兼容

- **决定**：保留现有 `chat(sessionId, request)` 与三参数 `chatProfile`。给后者增加显式 `ProviderInvocation(processId, iterationNo)` 的兼容重载，仍由同一 DefaultProviderService 路由到同一 execute/audit/retry。循环使用带会话的 Profile 公共入口，不另造模型客户端。
- **决定**：AuditStart/AuditRejection 增加可选调用关联并保留原构造器。storage 在首个 begin 或 reject 的**同一事务**建立 `llm_request_links`；重试验证并复用同一映射。链接失败零发送，旧入口不生成虚假 processId，不回填旧审计。
- **理由**：Provider 在内部生成 requestId。返回后才补映射会留下“已经外呼、进程崩溃但无法精确关联处理”的缺口。显式参数也避免借 ThreadLocal 猜审计归属。
- **比较方案**：按时间/session 猜映射、循环生成另一个 requestId、复制 Provider 调用链均不采用。兼容扩展只增加元数据，不改变显式路由、重试和旧入口语义。

## R05：迭代与工具去重

- **决定**：接受的每个 Provider 响应计一次，默认 10；第 N 次响应仅文本可完成，含工具则保存后 MAX_ITERATIONS，零末轮工具、零 N+1 请求。工具按响应顺序串行。
- **决定**：同响应先整体检查重复 ID，再允许任何工具执行。去重键为 `(processId, rawCallId)`；规范参数及工具名相同复用已提交观察，冲突终止。观察正文经 Memory 保存，与只含元数据的工具审计分开。
- **理由**：保持已批准决定，避免重复副作用和伪造“成功”。
- **比较方案**：课程末轮继续工具、并行工具、按名称去重、跨处理猜重复均不采用。

## R06：原始调用身份与发送视图

- **决定**：每个消息组内工具出现位置生成稳定 wire ID：`t_<processUUID去连字符>_<responseOrdinal>_<callOrdinal>`。投影同时重写 ASSISTANT 的 calls 与 TOOL 引用；原始 callId 保留于持久状态及去重，不能被 wire ID 替换。
- **理由**：现有 `OpenAiMessageMapper.validate` 要求整份消息中调用 ID 唯一；模型重复原始 ID、或不同处理返回相同 ID，都会与原样回放历史冲突。
- **比较方案**：放宽原 Provider 校验会改动 US1 既有契约，不采用；丢弃全部工具历史会破坏对话连贯性。

## R07：上下文来源与文件生命周期

- **决定**：启动 ProfileLoader 同批生成 `profileName → AgentSource` 不可变索引；只登记最终有效 Profile，重名/非法候选不得留下来源项。不能假定 Profile.name 等于目录名。
- **决定**：每次构建重读 AGENT.md、Bootstrap 和绑定 SKILL.md；运行配置、来源目录和技能绑定仍是启动快照。AGENT.md 缺失报错；Bootstrap 非空列表是显式引用，空/缺省列表采用可选 `.aegisos/AGENTS.md`、`SOUL.md`、`USER.md`，缺失 WARN。现有 Profile 已归一空/缺省列表，因此不虚构两者差别。
- **决定**：每次重验真实路径、相对软连接及目标快照、普通文件、UTF-8 和大小；先有界读 max+1 再判定。采用批准根内的目录描述符遍历/NOFOLLOW_LINKS 读取已解析目标；平台不支持所需安全读取能力时明确拒绝，不悄悄降级。Skill 仍须指向启动目标，不从 YAML 新增 skills。
- **理由**：文件正文可立即变化，绑定关系不可无声热切换；只启动检查路径不足以防下一轮越界。
- **比较方案**：按 Profile 名拼目录、每轮扫描 YAML、缓存正文不采用。此应用层控制仍不等于敌对本地用户或多租户的 OS 隔离。

## R08：上下文与资源预算

- **决定**：部署必须给出 `aegisos.react.max-context-bytes`，单位是确定性规范 JSON 的 UTF-8 字节；包含消息字段/调用元数据、工具 schema 和输出参数，不声称等于厂商 Token 或 SDK HTTP 包体。
- **决定**：另限制单消息、文件、工具参数/结果、每响应调用数和模型输出 Token。必保留系统指令、当前请求、工具 schema 超限则零模型请求；先移除可选长期记忆，再裁剪最旧历史轮次，最后裁剪当前处理最旧已闭合工具交换组，不能破坏配对或静默裁短正文。
- **决定**：Provider 传输增加 1 MiB 响应读取硬上限，使用有界流读取，超限为不可重试协议错误；对旧正常调用无语义变化，保留零自动重发。
- **理由**：20 轮窗口本身不能限制单条内容；现有传输使用 responseBody.bytes()，仅在循环收到对象后检查不能约束原始缓冲。Token 上限也不能替代有界读取。
- **比较方案**：不引入各厂商 Tokenizer 或隐藏总结调用；不把字节预算宣称为 Token 精确计费。

## R09：敏感内容拒绝

- **决定**：core 定义不透明 SensitiveContentGuard；boot 从 Provider 同一凭据解析快照装配，只提供检查能力，不向 core 暴露环境或密钥获取方法。
- **决定**：用户输入、上下文文件、恢复历史、模型全部不可信字段、解析后的工具参数及工具结果，在对应保存、转发、输出前检查。命中即固定 `SENSITIVE_CONTENT` 并停止，不替换后继续。保留此前安全提交的历史。
- **理由**：这是已确认的 FR-033；各层独立读取环境会使模型所用密钥与检测清单漂移。
- **比较方案**：任意未知/编码秘密的完美检测不作承诺。识别已知完整值和明确敏感载体，正常文字“API key 应来自环境”与 `credential-ref: env:NAME` 不误拒绝。
- **事实边界**：响应检查发生在 Provider 已外呼之后，不能改写其真实成功审计；工具已执行亦保留真实外部结果，Agent 另记观察被拒绝。固定安全错误不能声称撤销已发生的网络操作。

## R10：http_get 与 Sandbox

- **决定**：tool 显式依赖 Boot BOM 管理的 `org.apache.httpcomponents.client5:httpclient5`；当前基线解析为 5.6.4/httpcore5 5.4.3，不独立写版本。Jackson 3 参数解析显式声明 BOM 管理依赖，不经 provider 内部实现获取。
- **决定**：exact scheme/host/port/method，批准路径及查询键；一次 DNS 解析检查全部地址，只选择一个批准公网地址并固定到本次专用 DnsResolver。保留原域名 URI/Host/SNI 与 TLS 校验。禁止系统代理、cookies、认证缓存、压缩协商、自动重试、重定向和地址 fallback；每次调用独立客户端及连接管理器并关闭资源。
- **决定**：拒绝本机、私网、link-local、元数据、保留及转换绕过地址；默认无目标授权。仅隔离测试显式策略允许具体回环地址。DNS、连接、读取及总执行 deadline 均有界，响应有界读取；不能用取消 Future 当作副作用未发生的证明。
- **理由**：先校验 DNS 再由普通客户端重新解析仍可被 rebinding 绕过。研究已核对 HttpClient5 的 DnsResolver 和禁重试/禁重定向 API。
- **比较方案**：JDK HttpClient 无直接本次 DNS pinning 接口；改 URL 为 IP 破坏 HTTPS 主机语义；手写 TLS 或依赖 provider 内部 OkHttp 均不采用。

## R11：schema 与参数校验

- **决定**：http_get schema 仅 object/properties/url:string/description/required/additionalProperties:false；长度与 URI 规则由执行边界校验。JSON 解析拒绝重复键、尾随内容、未知字段、非对象、超深和超限；不把 Provider 的基础 JSON 对象检查当作工具授权。
- **理由**：现有 ToolSchemaValidator 明确不支持 format、maxLength、oneOf、$ref 等字段；保持其原契约和测试。
- **比较方案**：为本轮一个工具扩展整个 JSON Schema 引擎不必要。

## R12：CLI、真实验收与依赖门禁

- **决定**：最小 `init/chat` 使用 JDK 固定参数解析，不新增 Picocli。init 在 Spring 之前运行，不碰库/凭据/模型；chat 从有界 UTF-8 stdin 接收**一次**请求，重新执行并指定 session 续聊。boot 用专用非 Web source 显式装配；无子命令保留现有 Web 基础启动。
- **决定**：五组课程测试加临时 SQLite/CLI 集成测试默认只用替身；每故事聚焦验证、Maven test/package，最终质量/秘密/依赖门禁。依赖新增仍须安全扫描，不能沿用旧 PASS。
- **决定**：独立 `real-react-smoke` / ReactWeatherSmokeIT / verifier。同会话两次用户处理，max_iterations=3、Provider retries=0，最多 6 次模型物理发送、2 次天气 GET，执行前防超预算，默认关闭。不复用旧 Provider 固定一次调用的验收判据。
- **理由**：现 CLI 未实现；普通 Web 启动或旧一次模型冒烟不能证明 ReAct。保留 Failsafe classesDirectory 防止 fat JAR 类加载错误。
- **比较方案**：REPL/完整 CLI 框架、多天气供应商、Web 业务入口与定时推送均后续处理。真实验收缺条件/零发现/跳过/旧报告均不算 PASS；机器链路核验之外，必须确认建议与天气事实相关。

## 研究阶段门禁

所有技术未决项已在 R01～R12 解决；未改变用户已批准的范围、版本或业务决定。宪法预检查通过：公共契约在 core、九模块不扩展、Memory/Sandbox 统一入口、敏感信息拒绝、每故事测试与持久审计均有设计。后续 Phase 1 按这些决定形成计划、数据模型、契约和验证指南；设计通过不代表实现或外部验收通过。

## 实施补充：平台能力实测

2026-10-22：macOS/Zulu 21.0.9 不提供安全目录流。用户选择保持目录描述符契约，在实际支持 SecureDirectoryStream 的 Linux 环境完成读取验收；macOS 明确拒绝。不引入弱化 fallback。

## R13：真实失败后显式非thinking路线（用户批准A）

- **证据边界**：首次真实验收第二次模型请求INVALID_REQUEST，厂商原始错误正文未保存；不能推断唯一根因或补写HTTP状态。官方thinking协议与源码未回传reasoning_content的缺口吻合，原FAIL保留。
- **决定**：全局Provider可选thinking-mode，仅显式disabled向每次请求生成thinking.type=disabled；缺省不生成扩展、不根据Provider身份猜测。启动快照、严格CLI YAML校验、无效标量在凭据解析前拒绝。Spring属性绑定null视作未声明，严格CLI YAML拒绝显式null。
- **验收**：ProviderThinkingModeTest核对生产装配/真实loopback wire、首轮/工具续轮、快照及拒绝；专用Java/Python配置必须disabled且参与指纹。工程门禁通过后仍需新的真实授权。
- **未采用方案**：不支持thinking工具续轮；不新增公共reasoning字段或数据库迁移，不在harness绕过生产mapper，不降低天气事实断言。

## R14：评审后的数据库锁协议修订

- **实测**：对 SQLite inode 持 POSIX record lock 会被同进程 JDBC 物理连接关闭释放；第二次同 JVM tryLock 失败后的 channel.close 也会释放已持锁。回归使用实际 Hikari 物理连接替换、独立 JVM 与临时库验证，不依赖 Python 类比。
- **决定**：生命周期只持独立 sidecar；打开 channel 前先在 JVM 内认领规范路径，并预检已有 sidecar 普通文件/单链接属性；打开后继续复核，拒绝候选也不得先打开同 inode 再关闭。数据库和 sidecar 必须是可验证 unix:nlink=1 的普通文件；硬链接和不支持此属性的环境安全拒绝。规范目标的 .lock 后缀保留给锁，避免第二数据源把首库 sidecar 当数据库打开并关闭。备份/迁移/恢复同步采用同一协议。
- **未采用**：不关闭连接池轮换、不沿用数据库 inode 锁、不用 flock 替代、不允许不同 sidecar 协调同一硬链接库；不承诺抵御同 OS 权限恶意替换文件。
- **历史**：此前设计与实现说明作为阶段历史保留，本节为当前协议；RED/GREEN 和最后工程门禁见 validation.md 的 T142～T150 增量。

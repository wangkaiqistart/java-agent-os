# US2 运行时与边界

本次增量沿用 Java 21、Boot 4.1.1、Spring AI 2.0.1 与九个 Maven 模块。当前实施状态以 `specs/003-react-loop/tasks.md` 和 `validation.md` 的逐阶段证据为准；真实天气验收必须单独启用，默认测试均使用临时库和隔离回环服务。

## 调用与存储

公共 Java 入口为 `AgentService.createSession(profileName)` 与 `process(AgentRequest)`。会话由运行时创建 UUID，持久绑定精确 Profile；未知或错误归属不自动创建。相同会话使用非等待锁，数据库另有 RUNNING 唯一约束。ProfileContext 仅服务当前同步调用，finally 清理，不用于猜测审计归属。

Provider 的 processId/iterationNo 通过四参数公共入口显式传递；requestId 仍由原 Provider 服务生成。PREPARED、模型审计、响应接受、工具审计、观察保存、最终完成均为短事务，不跨网络保持数据库事务。只有安全响应接受且提交后迭代数才增加。默认最多10轮；末轮含工具只保存 OPEN 意图并停止，不能执行末轮工具或调用下一轮模型。

会话和审计位于 V3 SQLite，进程持有规范数据库路径对应的运行锁。旧 V1/V2 库要求离线手工迁移，不自动修改个人库。重启后遗留 RUNNING/未结束轮次标记 INTERRUPTED，保留旧审计 STARTED 与 OPEN 组；恢复不重发模型或工具。

## 配置与正文

运行配置唯一来自 Agent 目录内 `profile.yml`；Provider、Profile 与技能绑定是启动快照。全局 `.aegisos/config.yml` 的 `aegisos.providers[]` 可选 `thinking-mode: disabled`，由生产mapper在首轮及每次工具续轮发送 `thinking: {type: disabled}`。缺省不传扩展，不根据名称/模型猜测；修改后重启生效。严格CLI YAML拒绝非法模式；该选项不代表通用thinking或thinking工具续轮支持。`AGENT.md`、Bootstrap 与已绑定 `SKILL.md` 的正文每次构建重新读取，不展开环境变量、不执行脚本。

第一条 SYSTEM 顺序为角色（可空）→ AGENT → Bootstrap → Skill → ISO-8601 时间与时区。Bootstrap 显式列表按声明顺序读取；空列表尝试 `.aegisos/AGENTS.md`、`SOUL.md`、`USER.md`，仅真正缺失记安全 WARN。Skill 按绑定名称排序，必须保持启动时的相对软连接目标；改绑定需要重启。长期记忆只经 MemoryService，生产默认空实现，没有读取个人 MEMORY.md 或接入 Mem0。

AGENT 正文批准根为当前 Agent 真目录；Bootstrap 批准根为 `.aegisos`；Skill 同时限定于绑定目标及公共 `.aegisos/skills` 根。使用安全目录描述符、NOFOLLOW_LINKS、有界读取、严格 UTF-8 和身份复核。需要实际提供 SecureDirectoryStream 的平台：本机 macOS 缺少该能力时明确拒绝为 CONTEXT_FILE_UNAVAILABLE；正向正文/会话验收使用 Linux。没有 macOS 弱化回退，不宣称抵御拥有同等操作系统权限的恶意写者。

## 上下文与敏感内容

容量按规范逻辑 JSON 的 UTF-8 字节计算，包含转义、消息结构和工具 schema，并非模型 Token。总预算必须显式配置；必需系统、当前用户和授权 schema 不得裁剪。默认读取最近20个 COMPLETED 用户处理，每个处理包含完整工具交换；FAILED/MAX/OPEN 不自动进入新 Prompt。

超限依次移除安全的可选长期记忆、最旧完整完成处理、当前最旧 CLOSED 工具组；不拆开 ASSISTANT/TOOL，不改变原始持久历史，不自动总结。WARN 和裁剪记录为固定事件元数据，保存失败停止。工具发送 ID 按持久 process/组/调用序号投影为 `t_<32hexUUID>_<组序号>_<调用序号>`，原始 ID 和参数保留给去重。

各输入、恢复历史、最终 Prompt、模型字段与工具结果均检查启动凭据快照及明确敏感载体。发现秘密即结束，不替换后继续，不保存或回显该内容。已发生外呼保留真实元数据，不能改成未发送。该检查不能识别任意未知密钥或编码。

## 工具与网络

生产仅注册 `http_get`。所有工具须走唯一 ToolExecutor → Sandbox → 前置持久审计 → 执行一次 → 完成审计 → Memory 观察保存。业务失败形成结构化观察，审计/保存/敏感故障停止。只有同处理、同原始调用 ID、同工具/规范参数，且终态审计与观察均可靠提交，才允许复用；不做隐藏重试。

沙箱 targets 默认空；批准项精确匹配 HTTPS scheme/host/port/GET/path/query 键。一次 DNS 检查全部地址，拒绝私网、混合或不能安全分类的结果；固定一个公网 IP，保留原 Host/SNI 与证书校验。禁止自动重试、重定向、代理、Cookie、认证协商与压缩；不切换备用地址。全局8执行许可、4在途 DNS；默认 DNS/connect/read/总执行为2/3/5/10秒，最多30秒。总截止时间主动取消请求并关闭连接。响应最多读取上限+1，超限立即关闭，不 drain 剩余正文。

这是应用层白名单和资源控制，不提供容器级或多租户强隔离。完整工具体系、其他 Memory 后端、Web 业务接口、通知和调度仍属于后续范围。

## CLI 与验收入口

init 在 Spring 之前完成，仅创建新 `.aegisos` 模板，已有目录/软连接拒绝覆盖，不建库、不解析凭据。chat 从唯一 workspace/config.yml 安全加载白名单 YAML，audit.path 相对 workspace 解析；专用非 Web source 显式装配，只读 stdin 到 EOF 一次，不实现 REPL。没有子命令时仍启动原 Web 基础入口。

chat stdout 固定七字段：sessionId / processId / status / iterationCount / reply / error / persistenceConfirmed；固定诊断只到 stderr。完成且持久确认退出0，用法/输入/配置/归属拒绝2，忙3，MAX_ITERATIONS4，运行/审计/保存失败5。退出总是关闭 context、Provider 客户端、SQLite 连接池和运行锁。

独立 real-react-smoke 默认关闭，专用 YAML / 临时新库 / 唯一 run-id / 受控凭据；同会话两处理、每处理≤3轮、总≤6模型发送/≤2GET、Provider重试0。请求前扣额度，无预探测/换源/隐藏重跑。天气按坐标、摄氏单位、时效、实际发送最新观察与十进制温度/非空建议断言；Python verifier 联合本轮 XML、白名单 JSON 和重新打开的审计才允许 PASS。A14 当前实际状态见 validation.md，不能把离线 harness 当真实验收。

2026-10-22一次已授权真实运行实际2次模型发送/1次天气GET：首轮与天气工具成功，工具回填后的第二次模型请求INVALID_REQUEST，验收FAIL且未自动重跑。首次失败时公共消息未保留DeepSeek thinking所需的reasoning_content，也没有显式关闭thinking；不得宣称支持该模式的工具续轮。源码缺口与官方协议一致，但原始厂商错误未保存，尚不能断言唯一根因。用户已批准A路线：显式关闭thinking的配置与适配已实现，专用验收YAML必须声明该字段并参与指纹。新版已获新授权复验PASS（3模型发送/1天气GET、零重试、同会话两处理完成、接受轮次2/1），原FAIL保留，见 `specs/003-react-loop/deepseek-compatibility-gap.md`；本轮US2批准范围已完成141/141项；尚未执行生产部署或自动开始后续故事。

V3 手工迁移与回滚见 [迁移说明](us2-migration.md)。完整使用步骤与本轮 JAR 的 strace 初始化探针见 [US2 指南](../../specs/003-react-loop/quickstart.md)。

## 代码评审修复后的当前边界（T142～T150）

上文 141/141 指原课程实现与已授权真实验收阶段。本次追加九项评审修复任务，具体门禁及完成状态以 tasks.md/validation.md 的增量节为准；没有新的真实模型或天气调用，旧 FAIL/PASS 保留。

- JSON 参数在保存前同时检查原文、标准转义解码字符串和直接相邻的字段/标量关系。敏感字段的占位引用须匹配完整值，`null actual-value` 等前缀不能伪装成占位符；不递归解释字符串内嵌 JSON，不承诺任意编码识别。
- 运行时只持规范路径对应的独立 `.lock` 文件；SQLite 连接池轮换和物理连接关闭不释放该锁。同 JVM 在打开锁文件前认领路径，避免失败竞争者关闭同 inode 描述符释放已有 POSIX 锁。已有锁文件须在打开描述符前检查（打开后复核），避免拒绝硬链接候选时释放原锁。数据库和锁文件必须是普通文件且可验证 `unix:nlink=1`；多硬链接或无法验证时固定 AUDIT_UNAVAILABLE。数据库规范目标的 `.lock` 后缀保留给锁文件，不能作为数据库路径。仅支持满足上述条件的本机文件系统；不承诺防御同等 OS 权限的恶意并发替换。
- Profile 的 max_iterations（1～100，缺省10）和 max_history_turns（1～1000，缺省20）在注册前校验。非法项没有 Profile/AgentSource，调用为 UNKNOWN_PROFILE；不会先保存 USER 再因配置上界失败。正常项隔离注册，循环防御检查保留。

评审修复已于2026-10-24完成，累计150/150项；最后macOS/Linux各334项测试及构建/静态门禁通过，独立复审21/21文件无剩余新增缺陷，秘密扫描0发现。详细过程见003 validation末尾；没有新的真实天气验收或Git提交/推送。

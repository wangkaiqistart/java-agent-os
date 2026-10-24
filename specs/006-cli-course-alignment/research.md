# 第18节 CLI：研究与设计决策

日期：2026-10-24。依据：7页课件、[规格澄清](spec.md#澄清记录)、`edf49bf`真实源码；包含一次只读研究agent复核，未做代码复审或工程测试。

## R1 命令树与依赖

- **决策**：Picocli4.7.7在cli统一注册12叶命令；boot注入服务端口，先解析帮助/输入再启动运行时。保留CliArguments兼容适配，严格拒绝重复/未知选项和原始错误回显。
- **理由**：课程p3要求声明式命令。现有[AegisOsApplication](../../aegisos-boot/src/main/java/com/aegisos/boot/AegisOsApplication.java)把任意首个`--`参数送入Web，root help会错误启动；[CliArguments](../../aegisos-cli/src/main/java/com/aegisos/cli/CliArguments.java)只识别init/chat。
- **替代**：继续手写解析不能补齐课程方向；引入新模块或升级Boot无必要。
- **版本事实**：`https://repo.maven.apache.org/maven2/info/picocli/picocli/4.7.7/picocli-4.7.7.pom`通过curl TLS请求成功，POM坐标核对；系统Python首次请求因本机CA失败，未关闭TLS，改用系统curl。漏洞扫描/Java兼容尚未执行，后续必须验证。

## R2 元数据不能复用重启动

- **决策**：轻命令无Spring；boot复用安全YAML/Provider定义/Profile字段映射，提供不含客户端的元数据服务。ProfileLoader需要的ProviderRegistry使用仅元数据registration，adapter为空且明确不可执行；不据此把合法配置判为非法。
- **理由**：[ProviderConfiguration](../../aegisos-boot/src/main/java/com/aegisos/boot/config/ProviderConfiguration.java)会解析真实凭据并构造客户端；[ProfileLoader](../../aegisos-boot/src/main/java/com/aegisos/boot/config/ProfileLoader.java)依赖Registry，但并不需要真实外呼。
- **替代**：new完整Spring上下文会违反轻路径；复制宽松YAML解析会绕过未知字段/环境引用/敏感约束。
- **核验**：使用与生产同一映射器，坏项隔离；凭据缺失时仍可显示“配置有效/未探测”，只输出安全字段，不回显原文件。

## R3 会话统一边界

- **决策**：core SessionIdentity与SessionStore原子端口；AgentService校验Profile后经MemoryService委托，storage负责事务。不创建第二套SessionManager/ReAct。
- **理由**：[MemoryService](../../aegisos-core/src/main/java/com/aegisos/core/memory/MemoryService.java)已继承SessionStore；[DefaultMemoryService](../../aegisos-memory/src/main/java/com/aegisos/memory/DefaultMemoryService.java)负责敏感及预算检查。现有UUID/处理/审计必须继续使用。
- **替代**：按PDF改主键和messages_json会丢失既有规范交换组与审计语义，用户明确选择保留V3形式；CLI内拼接session_id无法可靠避免分隔符碰撞。
- **并发**：绑定与会话同事务；唯一约束+回滚后重读，不能只加JVM map或先查后插。已有工作区独占锁不替代数据库唯一约束测试。

## R4 V4与历史

- **决策**：新增V4完整schema及V3→V4手工离线迁移，原V1/V2/V3脚本和002/003/005证据不改写。旧会话无身份回填，显式UUID仍恢复。
- **理由**：[AuditSchemaInitializer](../../aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditSchemaInitializer.java)按全量规范DDL精确验证，新增表必须同步校验器和版本。`schema`创建与`migration`升级是不同入口。
- **替代**：启动自动ALTER、修改旧V3脚本、迁移个人库均不符合批准边界。
- **重开**：校验完整数据库投影与审计关联，不把loadSession最近1000条窗口当完整历史；超窗口测试使用分页/独立只读比对。

## R5 只读查询的副作用

- **决策**：core只读SessionCatalog契约，storage独立只读实现，不进入初始化/recovery。只在已有、安全、可验证且没有写运行时占用的库上查询，持非创建sidecar读锁，实际JDBC mode=ro/query_only组合必须测试。WAL/SHM无法保证零写入即拒绝。
- **理由**：[AuditStorageConfiguration](../../aegisos-storage/src/main/java/com/aegisos/storage/audit/AuditStorageConfiguration.java)会取运行锁/建池/建库；[RuntimeRecoveryService](../../aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeRecoveryService.java)构造即写入RUNNING→INTERRUPTED；[RuntimeDatabaseLock](../../aegisos-storage/src/main/java/com/aegisos/storage/runtime/RuntimeDatabaseLock.java)会创建db及sidecar。
- **替代**：直接加载完整配置会悄悄写库；普通JDBC可能创建缺失库；immutable会忽略活动变化，均拒绝。
- **验证**：目录清单、字节摘要、mtime、sidecar和遗留RUNNING逐项比较；同JVM和跨进程锁竞争，不能解锁原持有者。缺失lock或平台属性不可验证时固定QUERY_UNAVAILABLE，不自行创建。

## R6 REPL与旧脚本

- **决策**：TTY交互/pipe单次JSON、互斥开关。工作区与Profile继续显式提供；无隐式默认Profile。interactive使用cli/local/Profile三元组，`--user`可区分逻辑用户；batch保留每次新UUID或显式session。user不是认证身份。
- **理由**：[ChatCommand](../../aegisos-cli/src/main/java/com/aegisos/cli/ChatCommand.java)先读有界输入再创建会话，须保留；[CliChannel](../../aegisos-channel-cli/src/main/java/com/aegisos/channel/cli/CliChannel.java)七字段JSON被US2脚本使用。
- **替代**：统一默认REPL会吞掉管道兼容；Scanner/无界readLine会在校验前分配过大内存；每条重启Spring影响会话/锁，均不采用。
- **行为**：首条quit/EOF零会话；输入/模型/持久化/输出失败后非零结束，不自动重发。不得回显用户原输入；安全回复经完整检查再输出。

## R7 Profile管理与未来能力

- **决策**：创建使用受控模板和CREATE_NEW；目录名与新Profile名称一致，显式Provider/model且校验已配置项。删除先验证来源和目录边界，再原子移到`.aegisos/deleted-agents/<随机UUID>`，不递归遍历删除链接目标，不覆盖旧回收目录；无自动清理。仅后续启动快照生效，旧审计与会话不动。
- **理由**：不把任意路径或未经解析的YAML透传为新配置；可恢复的定义移出避免半删，故意不承担公共技能、会话删除功能。
- **替代**：任意递归rm、覆盖已有目录、实时更新运行中Registry均拒绝。
- **未来模式**：serve只启动已有Web基础，CLI入口使用批准工作区配置并本机监听；gateway返回未启用。无参数旧Web启动保留，不能把两个路径的不同配置入口混称相同快照。

## 研究结论与未执行项

架构/数据/默认行为已确定，无阻断设计的未决项。实施时验证Picocli解析和平台TTY行为、JDBC只读/sidecar锁、原子文件移动能力及全部安全门禁；能力不支持时明确失败，不降级安全保证。Maven、漏洞扫描、真实天气、个人库迁移均未在本阶段执行。

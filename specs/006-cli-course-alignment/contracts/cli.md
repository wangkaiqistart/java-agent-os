# CLI契约：命令、模式与输出

设计状态，以下新增命令实施后才可运行。统一入口为`java -jar <boot.jar> <command>`；文中`aegisos`仅表示同一JAR命令别名，不新增安装器。

## 命令矩阵（12个叶命令）

所有命令及组支持`-h/--help`，根支持`--version`；帮助不要求workspace/凭据，不启动Spring、不写文件、不联网。成功0，未知/重复/缺值/冲突2；覆盖Picocli默认异常handler，不打印原始参数、堆栈或未经检查的异常信息。

| 命令 | 参数（除帮助外workspace必填绝对路径） | 行为与启动边界 |
| --- | --- | --- |
| init | `--workspace` | 原有离线安全初始化；不覆盖、不建库、不解析凭据 |
| status | `--workspace` | 轻路径，报告配置/能力/schema/锁可确认状态；不探测网络、不recovery |
| chat | `--workspace --profile NAME`，可用`--agent NAME`替代；`--session UUID`；`--interactive`或`--batch`；交互可用`--user ID` | 首次消息后选择会话；仅需Agent执行才使用非Web上下文 |
| serve | `--workspace`，`--port`默认8080，范围1～65535 | 现有Web基础；本入口仅绑定127.0.0.1，无Agent业务接口；使用workspace批准配置，不能把命令参数透传为任意Spring配置 |
| gateway | `--workspace` | `CAPABILITY_DISABLED`，退出6，原因“多通道尚未交付”；零Spring/模型/工具/库写 |
| profile list | `--workspace` | 轻路径，安全名称/说明/状态/固定诊断；坏配置独立显示不可用 |
| profile create | `--workspace --name NAME --provider NAME --model NAME` | 安全模板新建，重名/未知模型/敏感参数拒绝；不外呼或改正在运行的快照 |
| profile show | `--workspace --name NAME` | 仅白名单元数据；不原样cat文件、指令、endpoint/凭据值或任意扩展字段 |
| profile delete | `--workspace --name NAME` | 按已验证来源原子移出agents到回收目录；无递归/级联；告知下次启动生效 |
| provider list | `--workspace` | 已配置名称、允许模型、适配类型、配置状态；不解析真实密钥、不构造客户端、不声称已连接 |
| tool list | `--workspace` | 实际内置http_get及启用/策略状态；不把未来9工具清单当已实现，不执行Tool |
| session list | `--workspace`，可选`--profile NAME --limit N --offset N` | 严格只读SessionCatalog，安全摘要分页；无正文/工具参数，不初始化/恢复 |

轻命令为所有帮助、init、status、Profile CRUD、provider/tool list及gateway拒绝；session list为独立只读storage路径，无完整Spring；chat为非Web重路径；serve为Web基础重路径。

### 工作区与Profile

- workspace保持US2必填绝对路径，不隐式探测个人目录；Profile保持显式选择，不新增默认字段。原config.yml白名单安全规则继续使用。
- profile唯一入口为Agent目录内profile.yml；skills来自已绑定目录，不接受旧profiles目录或frontmatter。
- `--agent`和`--profile`即使同值也不可同时出现；任何选项重复即拒绝。不得把Profile/config YAML的key当命令行秘密注入口。
- 创建仅安全模板，不接受任意文件拷入；删除失败不得部分删除。若安全路径/原子移动/权限条件不足则固定失败，保留原目录。

## chat默认与显式模式

| 条件 | 结果 |
| --- | --- |
| 有`--interactive` | 有界逐行REPL，允许重定向测试；与--batch互斥 |
| 有`--batch` | US2读取stdin到EOF一次并输出七字段JSON |
| 无开关且确认交互终端 | 默认REPL |
| 无开关且管道/非TTY/无法可靠判断 | 默认batch，IDE需要交互可显式加--interactive |

TerminalDetector可注入测试；真实JAR测试分别用PTY与pipe验证，不以替身测试代替终端默认验收。

### 会话选择

- interactive无--session：第一条有效消息后，使用`(cli, --user或local, profile)`集中getOrCreate；同工作区相同三元组退出再进复用UUID。user是受限逻辑名，不读取OS账号，不代表身份认证。
- batch无--session：维持US2每次新UUID语义，不建立三元组绑定；batch不接受--user，防止用户误认为身份已生效。
- 显式--session：只恢复对应Profile的已存在UUID，不认领/建立三元组；不允许再显式--user。是否已绑定不改变原UUID恢复规则。
- 首条quit/EOF不创建空会话；运行时按既有规则建立新空库不属于业务会话，验收分别统计。

### 输入与终止

- REPL逐条读入并处理，最大字节数沿用US2 ContextBudget，不使用无界readLine/Scanner。严格UTF-8，CRLF去除分隔符；末尾无换行的非空行可处理。
- 去首尾空白后等于`/quit`仅用于控制，不写历史、不外呼；空白行忽略、零业务处理，batch空白仍按旧契约拒绝。
- 不回显用户输入；正常交互显示安全session标识及回复，提示符/能力说明进入stderr。batch stdout只含原七字段JSON，不能混入Spring banner、提示符、日志或模式说明。
- 每次结果可靠提交后才接收下一条。输入/模型/持久化/输出失败结束chat，使用对应非零码，无自动重发。PrintStream.checkError等输出失败不可假成功。
- 含已知运行时密钥的输入/正文/结果直接结束本次处理；不发送、不保存、不回显该内容，不以替换内容继续。
- EOF/quit关闭本次上下文、Provider传输、池和自身运行锁；失败和中断也必须释放自身资源，不解锁其他运行者。

## 输出与退出码

- chat batch保留`sessionId/processId/status/iterationCount/reply/error/persistenceConfirmed`七字段及US2含义；错误时尚未确认的身份必须null，不反射原始参数。
- 非chat命令stdout为固定安全JSON对象；列表包含`items`及状态，session分页包含limit/offset/hasMore。诊断stderr只固定代码/安全说明；help/version为文本例外。
- 配置展示仅白名单投影；status仅报告CONFIGURED/INVALID/UNKNOWN、DATABASE_NOT_INITIALIZED/MIGRATION_REQUIRED/AVAILABLE/QUERY_UNAVAILABLE等确定状态；“AVAILABLE”只代表本次可读，不意味着远程连接或服务正在运行。
- 保留US2：0可靠完成；2输入/配置/归属/敏感拒绝；3SESSION_BUSY/WORKSPACE_IN_USE；4MAX_ITERATIONS；5Provider/审计/持久/运行失败。新增6专用于未启用能力。查询缺库/需迁移/不可读非零（2/2/3或5按原因），不能空成功。profile list含坏项返回2但保留合法项投影；单纯空合法列表可0。
- gateway业务执行返回6，gateway --help仍0。serve实际监听就绪与正常关闭才可称启动通过，不以注册命令代替服务运行。

## 旧入口兼容与模式差异

保留无参数的旧Web基础启动；保留development指南已记录的单参数`--spring.profiles.active=dev`或`--spring.profiles.active=metrics`旧启动路径，并逐条测试；不继续接受任意未知root选项。新root --help/--version必须先拦截；新serve使用workspace快照，与旧无参数Web配置来源分别记录。两者都无新Agent HTTP接口。

完整Web业务和gateway均延期，故本轮只能验收chat↔serve基础模式使用同一工作区/数据不丢失，不能宣称PDF完整三模式互通或gateway对话通过。第18节真实天气验收需新授权、预算与新证据，旧US2真实记录只作基线。

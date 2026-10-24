# 第18节 CLI 使用指南

CLI 使用可执行 JAR，不需要安装名为 `aegisos` 的全局命令。Java 21、九模块、Boot 4.1.1 / Spring AI 2.0.1 保持不变。当前实现与证据见 [006 验证记录](../../specs/006-cli-course-alignment/validation.md)，阅读及审查入口见 [变更、Review 与验证](006-cli-course-alignment-changes-review-verification.md)。

## 初始化与帮助

在仓库根目录执行。下面只创建新的临时工作区，不访问个人库：

```bash
mvn -B clean package
CLI_JAR="$PWD/aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar"
CLI_WORK="$(mktemp -d /tmp/aegisos-cli18.XXXXXX)"
java -jar "$CLI_JAR" --help
java -jar "$CLI_JAR" profile --help
java -jar "$CLI_JAR" init --workspace "$CLI_WORK"
```

所有命令都支持 `--help`，帮助不要求 workspace 或凭据，不启动 Spring、不写工作区、不联网。业务命令必须明确传入绝对路径 `--workspace`。初始化仅新建安全模板；已有 `.aegisos` 时退出 2，不覆盖。

## 12个命令与真实能力

| 命令 | 用途及限制 |
| --- | --- |
| `init` | 离线初始化 YAML、指令及技能目录，不建库 |
| `status` | 报告配置与当前可确认的数据库状态，不做网络探活或恢复 |
| `chat` | 统一 AgentService 的 REPL 或单次 JSON，无独立推理引擎 |
| `serve` | 本机 Web 基础、健康接口；无 Agent 业务 API |
| `gateway` | 明确返回 `CAPABILITY_DISABLED`，退出 6；多通道尚未实现 |
| `profile list` | 安全配置列表；坏项固定诊断，合法项仍展示，含坏项退出 2 |
| `profile create` | 明确 Provider/model，安全模板新建，不覆盖已有定义 |
| `profile show` | 只显示白名单元数据，不原样输出 YAML/正文/凭据/endpoint |
| `profile delete` | 按已验证来源原子移到回收目录，不删除公共技能及历史 |
| `provider list` | 配置名称、允许模型与配置状态；不解析密钥、不声称连通 |
| `tool list` | 当前仅 `http_get` 及策略声明状态；不执行工具 |
| `session list` | 既有 V4 库的只读摘要分页，不输出消息正文或用户身份 |

```bash
java -jar "$CLI_JAR" profile list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" profile show --workspace "$CLI_WORK" --name weather
java -jar "$CLI_JAR" provider list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" tool list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" status --workspace "$CLI_WORK"
java -jar "$CLI_JAR" session list --workspace "$CLI_WORK" --limit 20 --offset 0
```

刚初始化时无数据库，status/session list 返回 `DATABASE_NOT_INITIALIZED`、退出 2，这是确定状态。查询不顺手建库。分页 limit 默认为 20、范围 1～100；offset 默认为 0、范围 0～100000；可加 `--profile weather`。按更新时间倒序、UUID 升序稳定排序，返回 `items/limit/offset/hasMore`。

## Profile 管理与配置

唯一全局入口是 `$CLI_WORK/.aegisos/config.yml`。Agent 的运行配置放在 `.aegisos/agents/<目录>/profile.yml`，任务指令放 `AGENT.md`。技能仍来自相对软链接绑定，不新增 YAML skills 字段。

```bash
java -jar "$CLI_JAR" profile create --workspace "$CLI_WORK" --name cli-demo --provider deepseek-main --model deepseek-flash
java -jar "$CLI_JAR" profile show --workspace "$CLI_WORK" --name cli-demo
java -jar "$CLI_JAR" profile delete --workspace "$CLI_WORK" --name cli-demo
```

新增与删除仅影响下一次启动的注册快照。删除后目录移入 `.aegisos/deleted-agents/<随机UUID>`；没有自动清理或恢复命令，不级联删除会话/审计。越界、链接或原子移动条件不满足时拒绝，保留原定义。

Provider/model 必须在全局配置中明确列出。凭据通过部署平台、环境变量或受控存储注入，YAML 仅填写 `credential-ref: env:DEEPSEEK_API_KEY`。全局连接、凭据及 Profile 都在启动/重启加载；正文继续逐轮安全重读。`thinking-mode: disabled` 是既有非 thinking 工具续轮路线。工具目标默认空，声明策略不代表已验证网络连通。

## 交互与批处理

以下对话命令会调用所配置的模型，手动运行前需准备自己的受控凭据与允许的模型预算。自动测试默认使用临时 HTTPS 回环替身，不调用真实模型。

```bash
# 确认TTY时默认交互；IDE或重定向可明确加 --interactive。
java -jar "$CLI_JAR" chat --workspace "$CLI_WORK" --profile weather --interactive

# 管道默认单次JSON；--batch可明确指定。
printf '%s' '你好，请简短介绍自己。' | java -jar "$CLI_JAR" chat --workspace "$CLI_WORK" --agent weather --batch
```

交互按行串行处理；空白行忽略，去首尾空白后的 `/quit` 与 EOF 退出。首条退出/EOF不创建会话，最后没有换行的消息仍可处理。输入按字节有界、严格 UTF-8，支持 CRLF。提示符在 stderr，不回显用户原始输入；回复通过敏感检查后才整体输出。任一输入、处理、审计、持久化或输出失败都会非零退出，不自动重发。

默认交互身份为 `(cli, local, Profile)`，第一条有效消息后集中获取持久 UUID；退出再进相同工作区/身份复用。可通过 `--user team-a` 指定逻辑分组，它不表示认证用户，不取操作系统用户名。显式 `--session <UUID>` 只恢复既有会话，不能与 `--user` 同用。batch 不接受 `--user`，无 session 时每次新 UUID、不建立三元组绑定。`--agent` 与 `--profile`、`--interactive` 与 `--batch` 均互斥；重复参数拒绝。

batch 保留七字段 `sessionId/processId/status/iterationCount/reply/error/persistenceConfirmed`；`/quit` 在 batch 中是正文。只有可靠提交的结果才算成功。退出码：0 完成；2 输入/配置/敏感拒绝；3 锁忙；4 轮数上限；5 Provider/审计/持久化/输出失败；6 未启用能力。

007 已将正文读取扩展为 Linux SDS、macOS Darwin目录FD和Windows本地NTFS句柄；不再将macOS缺SDS作为对话拒绝条件。平台能力与身份无法验证时仍固定拒绝，无弱化回退。IDEA、PowerShell/Bash、YAML配置及真实平台状态见 [三平台运行指南](cross-platform-runtime.md) 和 [007验证](../../specs/007-cross-platform-runtime/validation.md)。

## V4 与严格只读查询

新空库直接创建 V4。V1/V2/V3 非空库都要求停机、一致性备份并显式离线迁移；运行时不自动 ALTER，不在这里迁移个人库。旧 V1→V2 与 V2→V3 步骤沿用 [Provider 历史](../../specs/005-provider-course-alignment/quickstart.md)和 [US2 迁移指南](us2-migration.md)，然后执行新增 [V3→V4 SQL](../../aegisos-storage/src/main/resources/db/migration/sqlite/V004__cli_session_bindings.sql)。先在临时副本演练，失败恢复停机备份。

V4 新增三元组→UUID 绑定，保留 V3 消息/交换组/审计分表。旧 UUID 不自动认领身份，显式 session 仍可恢复。`loadSession` 的上下文窗口不等于完整存档；全档证明独立比较消息、次序、完整 Tool 交换和审计。

只读查询不装配写数据源、初始化器或恢复器；只打开既有普通单链接库和既有安全 sidecar，持与写者互斥的短锁，SQLite 使用 `mode=ro` 与 `query_only`。缺锁、活动写者、路径/硬链接异常返回 `QUERY_UNAVAILABLE`、退出 5；旧库 `MIGRATION_REQUIRED`、退出 2。WAL/SHM/journal 存在或库头声明 WAL 时，目前一律拒绝，避免只读连接产生 SHM；不使用 immutable 或读写回退。读操作不更新 mtime、不把 RUNNING 恢复成 INTERRUPTED。

## Web 基础与停止

```bash
java -jar "$CLI_JAR" serve --workspace "$CLI_WORK" --port 18080
# 另一终端：
curl http://127.0.0.1:18080/actuator/health
```

端口默认为 8080、范围 1～65535，固定绑定 127.0.0.1，只接受批准的工作区配置。就绪后输出固定 `SERVING / WEB_BASELINE_ONLY` JSON，Ctrl+C 或进程终止关闭资源并释放自有锁。运行期间同库只读命令会因占用拒绝。停止后可重开 chat，历史保留。旧无参数及单参数 dev/metrics Web 入口仍保留，其配置来源与 workspace serve 分开。

没有新增 Agent HTTP API，gateway 不启动运行时。完整 Web 业务、多渠道及完整三模式对话验收延期；本节已在新授权下通过独立真实CLI天气验收（3次模型发送、1次天气GET、重试0、同会话两处理及关闭重开），见[006验证记录](../../specs/006-cli-course-alignment/validation.md)末尾。旧 US2 真实 PASS 保留为独立历史；后续外呼按对应授权和预算执行。

## 离线验证入口

```bash
CLI_BUILT_AFTER="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mvn -B clean package
python3 scripts/verify-cli-package.py --jar "$CLI_JAR" --built-after "$CLI_BUILT_AFTER"
python3 scripts/verify-cli-migration.py --output /tmp/cli18-migration-report.json
```

Linux 的 package 探针要求 strace，并串行调用 `verify-cli-runtime.py`。需要 Python3、OpenSSL、keytool；Unix额外验证PTY，Windows验证显式交互，临时证书只进入验收子进程 truststore；不修改系统信任。macOS 可以增加 `--allow-no-strace`，此时网络系统调用证明必须记 NOT_RUN。完整门禁与报告见 [quickstart](../../specs/006-cli-course-alignment/quickstart.md) 和 [validation](../../specs/006-cli-course-alignment/validation.md)。

迁移脚本的 `--output` 只接受尚未存在的新路径；复跑请更换报告文件名。已有文件或软链接会明确拒绝，原文件不变。

# 第18节 CLI：实施后验证步骤

**当前状态**：实现已提供，以下为复跑方法；实际执行结果逐次记录在 validation.md，不以本指南代替证据。所有自动运行仅使用专用临时工作区/测试库，不指向个人库。完整用户操作见 [CLI使用指南](../../docs/engineering/cli-usage.md)。

## 1. 工程与聚焦测试

Java21、Maven及Linux安全文件系统就绪；macOS仅验证支持范围与SDS拒绝路径。以下构建串行执行；筛选类已在本轮新增，报告必须实际发现测试且无失败/错误/跳过，禁止`failIfNoSpecifiedTests=false`掩盖目标零发现。

```bash
mvn -B -pl aegisos-cli -am test
mvn -B -pl aegisos-storage -am test
mvn -B -pl aegisos-boot -am test
mvn -B clean package
bash scripts/check.sh quality
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

前三条验证解析/编排、绑定/迁移/只读、生产装配；clean package验证九模块与可执行JAR；quality实际为clean verify+SpotBugs；secrets检查历史和工作树；dependencies检查完整依赖图及数据源，失败也阻断。NVD凭据从已批准受控存储注入，不写命令/YAML；先核对本地helper存在，不打印密钥。

## 2. 帮助与轻路径

在仓库根执行；WORK变量只指向本轮新临时目录。新命令参数见[CLI契约](contracts/cli.md)，这里的名称和Provider需与init模板一致。

```bash
CLI_JAR="$PWD/aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar"
CLI_WORK="$(mktemp -d /tmp/aegisos-cli18.XXXXXX)"
java -jar "$CLI_JAR" --help
java -jar "$CLI_JAR" profile --help
java -jar "$CLI_JAR" init --workspace "$CLI_WORK"
java -jar "$CLI_JAR" profile list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" profile show --workspace "$CLI_WORK" --name weather
java -jar "$CLI_JAR" provider list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" tool list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" status --workspace "$CLI_WORK"
java -jar "$CLI_JAR" session list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" gateway --workspace "$CLI_WORK"
```

逐条核对退出码，不把末条码当全部结果。session list在未建库时明确DATABASE_NOT_INITIALIZED并非零；gateway固定CAPABILITY_DISABLED/6。帮助/查询均无Spring完整上下文/外呼/文件变化，init仅约定文件创建。检查目录清单、摘要、mtime和sidecar；日志没有banner不足以证明零启动。12个叶命令各自help均须覆盖，不能只测root。

```bash
java -jar "$CLI_JAR" profile create --workspace "$CLI_WORK" --name cli-demo --provider deepseek-main --model deepseek-flash
java -jar "$CLI_JAR" profile show --workspace "$CLI_WORK" --name cli-demo
java -jar "$CLI_JAR" profile delete --workspace "$CLI_WORK" --name cli-demo
```

核对CREATE_NEW、回收目录、下次启动生效和历史不变；重名/非法/路径逃逸/软链接反例由自动测试执行，不对真实目录试删。

## 3. REPL与批处理（默认使用替身）

先用boot的CliReplWiringTest/既有CliConversationTest注入受控Provider与本地工具目标。打包进程harness通过临时证书的HTTPS回环替身和允许的环境变量注入，不携带真实密钥，不放宽生产配置校验。

```bash
python3 scripts/verify-cli-package.py --help
python3 scripts/verify-cli-migration.py --help
```

完整参数已固定，package探针内部串行运行运行时矩阵；仅回环HTTPS，不调用真实Provider：

```bash
CLI_BUILT_AFTER="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mvn -B clean package
python3 scripts/verify-cli-package.py --jar "$CLI_JAR" --built-after "$CLI_BUILT_AFTER"
python3 scripts/verify-cli-runtime.py --jar "$CLI_JAR" --built-after "$CLI_BUILT_AFTER"
python3 scripts/verify-cli-migration.py --output /tmp/cli18-migration-report.json
```

Linux要求strace；macOS可给package加 `--allow-no-strace`，网络系统调用证明标NOT_RUN。运行时探针需要Python3/OpenSSL/keytool/PTY；临时CA与truststore只属于本次子进程，不修改系统信任。runtime已由package调用，通常无需重复执行。

断言：两条消息+/quit恰好两处理、同UUID、不同processId，第二次上下文含前轮安全历史；第一条quit/EOF无会话/processing/模型/工具，最后无换行非空行可处理。PTY默认交互；pipe默认七字段单次JSON；显式开关覆盖默认。空白、超限、坏编码、密钥、锁忙、输出失败均按契约，不存在隐藏重发。

## 4. 迁移、查询与重开

仅临时V3 fixture，在独占锁及一致性备份后演练V4迁移；脚本仅接受可选报告输出，不接受已有数据库参数。验证原表全量内容、UUID、sequence、完整工具交换、Provider/Tool审计关联不变，旧session无自动绑定；失败回滚、伪schema、重复执行拒绝。重开按三元组得到同UUID，逐维改变隔离，并发首次创建最多一绑定/一会话。

session list需独立核验只读：预置RUNNING保持不变；缺库/缺安全sidecar/活动写者/未知schema/WAL不安全均拒绝；不创建文件、不恢复、不释放他人锁。完整历史超过窗口时用独立分页投影核对，不以loadSession窗口证明全档。

## 5. serve与未来能力

```bash
java -jar "$CLI_JAR" serve --workspace "$CLI_WORK" --port 18080
```

只在临时工作区运行，另一个终端核对127.0.0.1监听及已有健康接口；通过受控停止验证资源释放。没有新Agent业务API、没有gateway对话；三模式完整验收明确延期。旧无参数Web入口单列回归，不混同配置来源。

## 6. 真实天气（默认关闭）

真实外呼默认关闭。本轮已获用户明确授权，并完成独立run-id的Linux真实CLI天气验收（3次模型发送/1次GET/重试0）；事实与审计证据见[validation末尾](validation.md)。后续运行仍需对应的授权与预算，先完成工程门禁，再明确Linux临时新库、模型/天气目标、处理/模型/GET次数预算、重试0及失败不自动重跑。通过环境变量注入凭据，配置仅credential-ref；不得把真密钥写入日志/聊天示例。

获批后可使用真实终端`chat --interactive --profile weather`发送两条天气消息并quit；不仅核验非空回答，还要核验实际天气结果/建议、同会话两处理、模型/工具审计计数、quit零外呼和关闭重开。使用独立run-id保留首个失败与后续结果。未授权=NOT_RUN；不能复制US2的天气PASS。

## 7. 证据与收尾

逐次命令/退出码、测试发现数/失败/错误/跳过、XML新鲜度、JAR/目录/数据库回读及安全报告写[validation.md](validation.md)。实现后独立复审审计、凭据、输出、线程、自动工具、路由六类边界，修复先RED后GREEN，追加任务不改历史。交付指南写`docs/engineering/006-cli-course-alignment-changes-review-verification.md`并从README提供入口；未实施前不编写完成总结。

迁移脚本的 `--output` 只接受尚未存在的新路径；复跑请更换报告文件名。已有文件或软链接会明确拒绝，原文件不变。

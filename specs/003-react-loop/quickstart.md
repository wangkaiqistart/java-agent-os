# US2 实施后验证指南

补充（2026-10-24）：下文141项是原课程实施阶段的记录；后续评审修复T142～T150已完成，当前累计150/150，最后macOS/Linux各334项测试通过。代码阅读、重点review及当前复跑方法见 [US2变更与验证指南](../../docs/engineering/us2-changes-review-verification.md)；完整历史见validation末尾。

**当前状态**：CLI、V3、ReAct、显式thinking-mode和默认关闭的真实harness已实现，离线/工程门禁及新授权A14真实天气验收均PASS，任务141/141；首次FAIL原记录保留，见validation.md。当前DeepSeek工具对话显式使用thinking-mode: disabled，不支持thinking工具续轮。以下真实命令会产生外呼，仍需操作者明确启用。生产正文读取要求SecureDirectoryStream，Linux完成正向验收，macOS明确返回CONTEXT_FILE_UNAVAILABLE；原Provider历史保留。

## 1. 环境与普通构建

使用Java21、Maven3.9.x，在仓库根执行。实施者先核对工作区，保留既有修改；本计划当前在main，功能目录为003-react-loop，不代表已切分支。

```bash
java -version
mvn -version
git status --short --branch
mvn -B test
mvn -B clean package
```

以上Maven命令当前就存在；US2实施后须发现并执行新增测试。默认构建不启用任何real-* profile，模型/天气外部请求为0，允许显式隔离测试回环服务。每故事均保留自己的聚焦及test/package证据，不等到最后只跑一次。

实施后聚焦示例（测试类以[验收契约](contracts/acceptance.md)为准）：

```bash
mvn -B -pl aegisos-core -am -Dtest=ReActLoopTest,PromptBuilderTest,AgentServiceTest,ContextLoaderTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl aegisos-tool -am -Dtest=ToolExecutorTest,HttpGetToolTest,SandboxPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -pl aegisos-boot -am -Dtest=SessionPersistenceTest,RuntimeRecoveryTest,CliConversationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`failIfNoSpecifiedTests=false`仅容许上游模块无匹配，不能证明目标测试存在；必须检查目标模块 `target/surefire-reports/TEST-*.xml` 与本轮开始时间，指定类全部被发现、tests>0、failures/errors/skipped=0，否则本次聚焦验证不通过。不能用这类命令代替全reactor test/package。

## 2. 初始化临时工作区

```bash
US2_WORKSPACE="$(mktemp -d "${TMPDIR:-/tmp}/aegisos-us2.XXXXXX")"
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar init --workspace "$US2_WORKSPACE"
```

预期：生成非敏感YAML/指令模板，未创建数据库、未监听Web端口、未外呼。再次初始化明确拒绝覆盖。不要直接对个人 `.aegisos` 测试迁移。

按 [配置契约](contracts/configuration-cli.md) 编辑该工作区 `.aegisos/config.yml` 与 weather/profile.yml：明确Provider/模型、必填上下文预算；凭据只保留env引用。使用本轮DeepSeek工具对话时，在全局`aegisos.providers[]`对应项显式设置`thinking-mode: disabled`，重启生效；不放到profile.yml。缺省不会自动关闭thinking，本轮不支持thinking工具续轮。默认targets=[]，未经明确批准工具将被拒绝。自动化离线验证使用测试装配替身；普通打包应用不会内置可绕过安全边界的“测试Provider”。

如需人工真实对话，先由受控环境注入DEEPSEEK_API_KEY，并明确批准天气域名/端口/路径/query。不要把真实值写进YAML、命令参数或聊天；启动不会做预探测。个人手动chat会发生真实调用，应与下一节受预算限制的正式验收区分。

## 3. 单次对话、续聊与重启

以下命令只在支持 SecureDirectoryStream 的 Linux、受控配置/凭据和外呼条件明确时运行：

```bash
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar chat --workspace "$US2_WORKSPACE" --agent weather <<'MESSAGE'
查询已批准城市的当前天气，复述工具给出的温度与单位，并给一条建议。
MESSAGE
```

预期stdout为安全JSON，包含sessionId/processId/status/持久确认及回复；诊断只到stderr。chat读取stdin到EOF处理一次后退出，不是REPL。把返回的会话身份填入下面命令；身份不是凭据：

```bash
US2_SESSION=替换为返回的会话UUID
java -jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar chat --workspace "$US2_WORKSPACE" --agent weather --session "$US2_SESSION" <<'MESSAGE'
沿用刚才本会话的天气结果，现在出门需要注意什么？
MESSAGE
```

两次不同进程使用同一库，须能恢复已提交历史；未知身份/错Agent零外呼，不静默创建。退出码0才是持久确认的完成；3为忙，4为达到上限，5为运行/审计/保存失败，详见CLI契约。故障注入/末轮/恢复中断使用默认测试，不靠反复真实请求验证。

默认数据库将为V3；已有V2只能提示MIGRATION_REQUIRED。迁移须另按 [数据模型](data-model.md) 的停机备份→副本预演→手工升级→重开核验执行，不运行自动升级，不仅复制活跃数据库主文件；本指南不授权迁移个人库。

## 4. 严格真实天气验收（显式启用）

这一节会发起真实 DeepSeek/天气调用，不属于默认构建；只有当前工程门禁通过并明确授权本次执行后才运行。专用YAML格式、事实断言和额度见 [验收契约](contracts/acceptance.md)。由操作者提供本次唯一run-id、受控凭据环境、明确目标批准和专用临时目录；Provider重试必须0，每处理最多3轮，全运行最多6模型/2GET。专用YAML的`provider.thinking-mode`必须为`disabled`，Java/Python均校验且参与指纹，并由harness传入生产运行配置。首次失败配置/证据保持原样，新运行必须新run-id；仅准备配置不产生外呼。

```bash
export AEGISOS_REACT_SMOKE_CONFIG=/绝对路径/本次react-smoke.yml
US2_SMOKE_STARTED_AT="$(python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat())')"
US2_SMOKE_MAVEN_EXIT=0
if mvn -B -pl aegisos-boot -am clean verify -Preal-react-smoke; then
  US2_SMOKE_MAVEN_EXIT=0
else
  US2_SMOKE_MAVEN_EXIT=$?
fi
python3 scripts/verify-react-smoke.py --config "$AEGISOS_REACT_SMOKE_CONFIG" --started-at "$US2_SMOKE_STARTED_AT" --maven-exit-code "$US2_SMOKE_MAVEN_EXIT"
```

上述条件捕获Maven实际退出码并始终执行验证器，不能固定传0。开始时间在执行前由操作者进程记录，经`--started-at`传入；不能仅信任结果JSON自报时间。验证器同时绑定配置中的本次唯一run-id/非秘密指纹与新报告；默认无配置、XML缺失/零发现/skip/旧报告不能PASS。运行失败停止，不自动再次执行；新的人工运行必须使用新run-id并重新明确预算。

证据至少包括：真实工具意图/有效天气/观察转发/事实匹配/建议断言、同会话两次完成、逻辑及物理次数、工具次数、审计关联及重开结果。报告仅元数据，不导出Prompt、密钥或正文。旧real-provider-smoke的单请求PASS不能替代此验收。

## 5. 工程门禁与记录

```bash
bash scripts/check.sh quality
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

依赖扫描需要正常数据源/工具与受控NVD凭据；失败仍阻断。macOS可先确认本地忽略目录的 `scan_with_keychain.py` 存在，再使用既有Keychain→扫描子进程注入方式；不打印或复制凭据。此辅助入口不是版本库必有脚本，CI仍使用Secrets。

执行证据写入 `specs/003-react-loop/validation.md`，按故事记录实际测试发现数、Maven退出状态、质量/秘密/依赖结果、真实验收PASS/FAIL/NOT_RUN与证据位置；不覆盖002/005历史，不把本计划当验证证据。结束时更新 [课程追溯](course-traceability.md)，并单独完成自实现循环/Memory/Sandbox的代码评审。

## 6. 离线验收程序自测与本轮 JAR 探针

Python 验证器需要 Python 3.11+ 与 PyYAML；不读取环境凭据、不进行外部请求。Linux 的 JAR 探针额外需要 strace，它核验初始化的 IP 网络调用与监听均为零。

```bash
python3 -m unittest discover -s scripts/tests -p test_verify_react_smoke.py
US2_PACKAGE_STARTED_AT="$(python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat())')"
mvn -B clean package
python3 scripts/verify-cli-package.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after "$US2_PACKAGE_STARTED_AT"
```

探针只对新建临时空目录执行两次 init，不运行真实 chat；检查二次拒绝覆盖、无数据库、无 Spring 运行时/凭据解析器加载、stdout 安全 JSON，strace 无 IP 外呼/监听。缺 strace 为 NOT_RUN，不能拿它当网络验收通过。包时间必须晚于执行前 UTC 锚，不能拿旧 JAR 验证新源码。

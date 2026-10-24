# 跨平台运行验证记录

基线：codex/006-cli-course-alignment，HEAD edf49bf，包含未提交006。2026-10-24用户明确要求开始实现三平台原生运行；执行证据仅追加007。快照.tools/cross-platform/baseline.tar.gz与分行指纹baseline.json。当前无commit/push、个人库或真实外呼。

## 准备

新增007规格、计划、任务及平台契约；取代当前SDS唯一实现限制，不改写003/006历史。实现后按macOS/Linux/Windows分别记录，Windows当前NOT_RUN。无spec扩展hook配置。

## TDD：首轮行为失败（保留）

- `context-red-corrected.log`：修正macOS `/var` 与 `/private/var` 测试夹具后重新执行，5项/1 failure/1 error/0 skip。failure为预期SENSITIVE_CONTENT却提前返回CONTEXT_FILE_UNAVAILABLE；error为正向读取直接被生产SDS检查拒绝。二者均为待修复的生产行为，不再是INVALID_SOURCE夹具错误。首次格式失败、原夹具失败日志另保留，不算有效行为RED。
- `chat-red.log`：生产CLI新两消息用例，5项/1 failure/0 error/0 skip，预期退出0，实际5。
- `permissions-red.log`：新增InitPermissionsTest，1项/1 failure/0 error/0 skip，实际初始化文件权限不是600。
- macOS原生读取完成后，新正文用例先成功，继承用例因旧的“macOS必须拒绝”预期失败（context-green-initial.log）。随后将四个既有测试能力判定改为真实临时文件读取探针；Linux强制SDS断言保留，不能只按OS名称判成功。

## 实现与工程验证（2026-10-24）

生产实现：core/platform 新增 SecureFileAccess、DarwinFiles、WindowsFiles、WindowsPrivateFiles、OwnerOnlyFiles；固定JNA5.17.0。ContextLoader保持来源/边界/UTF8/敏感检查，平台门面负责受控有界读取。RuntimeDatabaseLock保持独立sidecar/JVM先认领，单链接调用平台实现；AuditStorageConfiguration允许合法Windows盘符。init/Profile创建在创建时设私有权限，Windows使用初始受保护owner DACL。绑定元数据序列化为跨平台斜杠。

| 验证 | 本轮结果 | 执行证据（仓库忽略目录） |
| --- | --- | --- |
| core全量测试 | PASS，96项/0失败错误跳过 | `.tools/cross-platform/core-green.log` |
| 首次九模块package | PASS，退出0 | `.tools/cross-platform/package-initial.log` |
| 首次静态全量 | FAIL，SpotBugs提示Windows卷根可能为空；显式拒绝空根后修复 | `.tools/cross-platform/macos-quality.log` |
| macOS clean verify -Pstatic-analysis | PASS，106 suites/388 tests/0失败/0错误/0跳过；九模块SpotBugs无问题 | `.tools/cross-platform/macos-quality-corrected.log`、`macos-quality-counts.json`、`macos-quality-reports/` |
| Linux clean verify -Pstatic-analysis并强制真实SDS | PASS，106 suites/388 tests/0失败/0错误/0跳过；九模块SpotBugs无问题 | `.tools/cross-platform/linux-quality.log`、`linux-quality-counts.json`、`linux-quality-reports/` |
| Python全部回归 | PASS，23项，退出0 | `.tools/cross-platform/python-tests.log` |
| macOS新JAR探针 | PASS，退出0；中文空格工作区、batch/PTY/两消息同会话、成功审计和重开、CRUD/只读零写/serve停止释放/gateway拒绝 | `.tools/cross-platform/macos-package-probe.log`（包含runtime子探针） |
| Linux新JAR探针 | PASS，退出0；同上，额外strace确认init零IP联网/监听 | `.tools/cross-platform/linux-package-probe.log` |
| macOS/Linux临时V3→V4演练 | PASS，均退出0；备份、全档与Tool交换/审计保留、重开、回滚 | `.tools/cross-platform/{macos,linux}-cli-migration.log`及对应json |
| macOS/Linux临时V2→V3演练 | PASS，均退出0 | `.tools/cross-platform/{macos,linux}-react-migration.log` |
| Gitleaks历史与全工作目录 | PASS，退出0，均0发现 | `.tools/cross-platform/secrets.log`及根目录gitleaks-report*.json |
| 当前依赖安全 | PASS，内层真实退出0；108项扫描节点，漏洞记录0，包含JNA5.17.0及提取的jnidispatch DLL | `.tools/us1-evidence/authenticated-20261024-152234/command.json`、dependencies.log、dependency-check-report.json/html |

依赖扫描于07:22:34Z～07:23:12Z执行，凭据仅由现有macOS Keychain helper读取并注入扫描子进程环境；未记录凭据值。helper外层退出0不能替代内层码，本轮已核对command.json真实exitCode=0及新报告包含JNA。未复用006旧104项依赖报告。

macOS本机为64位Java21/APFS；Linux为OrbStack `aegisos-us2-tests` 的独立Linux临时工作树与Java21，本机映射用于读取源码/缓存，构建产物保存在Linux `/tmp/aegisos-us2-workspace`。两边Maven门禁分别串行执行；Linux明确设置 `-Daegisos.test.require-secure-directory-stream=true`。macOS完整门禁完成于07:16:52Z，Linux完成于07:19:09Z；报告已独立复制，未将上次XML当成本轮。

打包命令：`python3 scripts/verify-cli-package.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after <本轮构建前UTC锚点>`；macOS增加 `--allow-no-strace`。macOS锚点07:16:00Z、Linux07:18:00Z。进程探针仅临时HTTPS回环、临时CA/库，不访问真实模型或天气；模板替换/日志读取均显式UTF-8。macOS无strace：网络系统调用证明NOT_RUN，不冒充Linux strace结果。

## 独立复审与追加修复

使用 reviewing-code 的指定文件Standard流程，按本轮相对006工作树快照筛选文件，原006 review目录和进度先归档到 `.tools/cross-platform/006-review-archive/`。初始35文件，文档/CI完成后增至42文件；安全、健壮性、需求、风格、维护、依赖六维度独立审查，逐文件覆盖无遗漏。

首轮健壮性与需求维度重复发现一项Windows Python3.13默认locale编码问题：UTF-8中文模板在cp1252/gbk中可解码失败，导致Windows新回环入口提前退出。新增T019保留发现；修正config和class日志显式UTF-8读写后，macOS/Linux新JAR探针均PASS，需求独立增量复审确认关闭。首次结果保留在 `.reviewing-code-diffs/_result_robustness.json` 与 `_result_requirements-compliance.json`，关闭证据见 `_result_requirements-final.json`。安全/维护/风格/依赖维度首轮无可确认新增问题。最终置信度收敛与报告状态在下一条追加，不用本段替代最终复审。

## 历史与未执行范围

- 与实施前快照逐SHA256比较：58个002/003/005/006历史规格/任务/验证及数据库SQL文件完全不变，证据`.tools/cross-platform/history-integrity.json`；既有006源码/文档继续保留在当前分支，未commit/push。
- Windows：已提供64位本地NTFS原生实现、受保护DACL及CI矩阵，**NOT_RUN**。本机没有Windows实机，也未推送或触发远程CI；不能宣称三平台全部验收。新增T020保留实际Windows全量与进程验收待办。Windows CI先检查软链接权限，不能跳过链接安全用例；显式交互为自动入口，真实Windows终端/IDEA操作仍待手测。
- IDEA：已提供Java21/Application/模块classpath/UTF8/环境变量/参数的中文步骤，没有操控IDEA界面完成手测。
- 无真实模型/天气调用、个人库迁移、部署或Git交付。本轮通过的是回环生产装配、临时库与工程门禁。平台库/身份/权限无法验证仍固定拒绝，没有弱化读取开关。

## T021 最终增量：Windows路径别名防护

收尾自检发现：Windows扩展路径原生创建会保留尾随点/空格，JDBC/Win32可能裁剪；保留设备名也可能产生不同解释。新增纯词法 `WindowsFiles.requireSafeNames`，供localVolume和全部Windows原生入口共同使用，不在其他平台加载kernel32。拒绝尾随点/空格、NUL/CON/PRN/AUX、COM/LPT设备名及扩展名/上标变体；允许正常 `.aegisos`、中文和内部空格目录。

- `windows-names-red.log`：PlatformFilesTest 5项/1 failure/0 error/0 skip，证实旧规则未拒绝尾点别名；原RED保留。
- 修复后重新执行macOS与Linux完整clean verify/static-analysis，**两边均106 suites / 389 tests / 0 failure / 0 error / 0 skip，退出0**。Linux仍强制真实SDS。最终分别完成于07:29:53Z、07:29:55Z。
- 最终日志：`.tools/cross-platform/macos-quality-final.log`、`linux-quality-final.log`；独立报告与计数：`{macos,linux}-quality-final-reports/`、`{macos,linux}-quality-final-counts.json`。先前388项记录保留为修复前通过结果。
- 用最终新鲜JAR重新执行两边完整package/runtime进程探针，构建前锚点07:28:00Z，均退出0；日志 `{macos,linux}-package-probe-final.log`。回环两消息/审计重开、Profile CRUD、只读零写、serve释放锁和gateway拒绝全部通过；Linux额外strace PASS，macOS此系统调用证明仍NOT_RUN。
- 独立安全复查 `.reviewing-code-diffs/_result_security-final.json` 确认正常路径不误拒绝、所有Windows入口均接线，无新增可确认P0/P1；纯词法用例在双平台执行不等于Windows原生验收。
- 本增量没有依赖图变更；保留本轮07:23包含JNA的108项/零漏洞新扫描，不把旧006报告替代为本轮。Windows原生句柄/DACL的实际运行仍由T020跟踪。


## 最终收尾状态

独立评分增量复查已完成：42/42文件覆盖，UTF-8问题与T021路径别名防护共2项关闭，剩余P0/P1为0；`.reviewing-code-diffs/_result_scored.json` 和 `_report.md` 保留原发现、评分、复查与限制。当前Gitleaks重新覆盖T021及收尾产物仍0发现、退出0，日志`.tools/cross-platform/secrets-closeout.log`。命令形态/真实退出码汇总在`.tools/cross-platform/commands.json`；三OS矩阵YAML、交付文档本地链接与`git diff --check`检查通过，target仍被忽略。

007任务共21项，已完成20项，**T020 Windows真实运行验收未执行**。代码实现、macOS/Linux门禁与独立复审已完成，不能把该状态描述为三平台全部验收完成。用户可按[三平台IDEA指南](../../docs/engineering/cross-platform-runtime.md)在本机运行；待Windows实机或授权Git交付后执行CI取得实际证据。本轮没有commit/push、真实外呼、个人库变更或部署；无spec扩展hook配置。

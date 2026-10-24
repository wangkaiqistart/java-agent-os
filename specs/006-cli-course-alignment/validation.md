# 第18节 CLI：过程与验证记录

## 2026-10-24：课程核查、规格、澄清和设计

**本阶段结果**：已产出规格/计划/数据/契约/68项任务；尚未实施Java/POM。以下PASS只针对已执行的资料或文档检查，不是新CLI能力完成证明。

### Git与资料基线

- 起点main=`75fb02684fd19dfa139b71fbb3f01e396cc76317`；用户已暂存第18节PDF。
- 工作分支`codex/006-cli-course-alignment`从US2归档`edf49bf51b9bf5dbaeea02b98bf62adbf39a1218`创建，main及归档分支不改写。
- PDF：`docs/第18节：CLI 功能概述、实现思路与代码讲解.pdf`，7页。
- 原PDF SHA256：`0b3ecc1b2ab4340a89a39bb9c385343dea642c4a2ac052910281db3856542811`。
- 原暂存blob：`95bbada44105bfd0d7943c8696194c1d0cf3ffd2`；本阶段不另stage/commit/push。
- 用户三项答案均A：持久三元组→UUID且保留V3；12入口/未来能力明确未启用；TTY交互/pipe单次JSON+开关。已落实FR-029～031、场景、成功标准及追溯表。
- 旧002/003/005任务和验证历史不更改；US2历史334测试及真实天气不作为本节新PASS。

### 已执行命令与材料读取

| 操作 | 退出/结果 | 解释 |
| --- | --- | --- |
| 本地pypdf全文抽取、pypdfium2渲染7页并逐页查看 | 成功 | 页码/代码/表格/验收条目已核对；没有修改PDF或抽象为执行指令 |
| 系统Poppler读取 | 失败 | 缺`libgpgmepp.6.dylib`，改用已有PDF运行时，无系统库修改 |
| `check-prerequisites.sh --json --paths-only` | 0 | 活动feature正确解析为006 |
| `setup-plan.sh --json` | 0 | 使用项目plan模板，生成设计产物 |
| `setup-tasks.sh --json` | 0 | 使用项目tasks模板，设计文件均可发现 |
| Python直接请求Picocli POM | 失败 | 本机CA证书验证失败；未关闭TLS |
| curl经TLS获取Maven Central Picocli4.7.7 POM | 0 | 解析坐标为info.picocli:picocli:4.7.7；仅证明制品存在 |
| 只读研究agent | 已返回 | 核对Memory门面、V3精确schema、初始化/恢复写入、sidecar锁及纯元数据路径；不是实施后独立代码复审 |
| `git diff --check` | 0 | 当前受跟踪改动无空白错误；新文件另以文档核验脚本检查 |
| `git diff --cached --check` | 0 | 用户原暂存PDF未引入空白错误 |

本地抽取、渲染和核验工作文件放在已忽略的`.tools/lesson18/`，不入提交。未新增不必要的图片资产或重复改写原课件。

### 规格检查历史

- 首轮：13/16，三项未决；未进入设计。
- 用户答复后：16/16，新增通过项为“无澄清标记、无关键歧义、结果可直接设计验收”，无倒退。身份/存储、交互和范围已解决；性能/扩容无本轮新目标，配置/平台/安全依现有契约；实现方法与测试路径已在plan细化，无需新增用户选择。
- 当前31个唯一FR、7个SC、4个故事；CLI18-01～12都有页码→需求/结果→场景/任务。
- 68项任务连续唯一，全未勾选：准备3、基础5、US1 10、US3 14、US2 13、US4 13、收尾10；P标记仅分文件测试准备，所有Maven/clean串行。
- FR/SC任务覆盖索引位于[tasks.md](tasks.md)，将静态覆盖与实际实现/验收区分。
- `.specify/extensions.yml`不存在，specify/clarify/plan/tasks无注册before/after钩子需要执行。

### 设计产物静态核验

命令：`python3 .tools/lesson18/verify-docs.py`，退出0，文档范围PASS。

- 31 FR、7 SC、4故事、3条已接受澄清、16/16清单；68个连续唯一任务，已勾选0。
- 12条课程映射与12条任务入口；38/38 FR/SC均有任务索引，覆盖率100%。这只证明静态覆盖，不证明实现正确。
- 全部本轮Markdown本地链接存在、代码围栏闭合、无行尾空白；task格式和路径校验通过。
- 原PDF SHA/暂存blob一致；HEAD仍为US2归档提交；九模块源码/POM、002/003/005、README/AGENT/skill未改动。
- 未跟踪新增文件仅本feature文档；抽取/核验脚本和渲染文件均位于已忽略.tools。无新Java/SQL/POM实施产物。

后续只读speckit-analyze的交叉检查结论单独向用户报告，不在该只读环节写回文件；实施仍需首次任务范围确认。

### 当前未执行/待确认

| 项目 | 状态 |
| --- | --- |
| Java/POM/DDL实现与行为RED/GREEN | NOT_RUN，等待首次任务范围确认 |
| Maven test/package/quality/SpotBugs | NOT_RUN，本阶段无Java变更 |
| secrets/dependencies当前工程门禁 | NOT_RUN，后续实施须重新执行，不复用旧扫描 |
| Linux生产正文/新REPL/新schema装配 | NOT_RUN |
| 新JAR的12入口/PTY/只读/迁移harness | NOT_RUN |
| 第18节真实天气 | NOT_RUN，无本轮外呼授权；需工程门禁后单独申请 |
| 个人库迁移、部署、commit/push | 未执行 |
| messages_json实列 / 完整三模式 | 批准偏差 / 延期，不计通过 |

根据课程skill第5步，任务与差异已具备可审查产物后才请求首次实施确认；不重复请求已完成US2的授权。本轮后续验证追加在本文件，保留上述原始状态，不回写002/003/005。

## 2026-10-24：实施已授权

用户明确确认按68项任务实施；真实外呼、个人库、commit/push仍未授权。requirements清单16/16，无未勾选。实施基线与暂存快照保存.tools/lesson18/implementation/start.json；Java21.0.9、Maven3.9.14/macOS aarch64。后续日志按baseline、RED、GREEN、package、quality、secrets、dependencies、Linux、进程分开保存；XML逐次检查发现数和新鲜度。当前.gitignore已覆盖target、.tools、db/sidecar、日志、环境和凭据文件，无需改动。

### us1-green

改动前mvn test退出0；首轮foundation-red被本机私有镜像DNS失败阻断，不算行为RED。临时Central设置重跑后CliDispatchTest三例中2个INVALID_CLI_ARGUMENTS行为失败，确认新解析缺口；修复后foundation-green-cli退出0。us1-red为help/元数据3个行为断言失败；us1-green与us1-package均退出0。新JAR探针退出0，12叶help/元数据/init无覆盖无建库通过；macOS无strace，网络系统调用证明NOT_RUN，留Linux。完整日志均同目录保存，未修改全局Maven设置。

报告快照：.tools/lesson18/implementation/us1-green/counts.json；当前全量XML：{'suites': 92, 'tests': 342, 'failures': 0, 'errors': 0, 'skipped': 0}。只计本轮全量运行所覆盖报告。

### binding-green

core身份骨架RED为2个预期行为失败，补验证/安全toString后通过；storage RED为绑定能力/新库版本缺口。绑定/Schema实现后首轮全量仅旧ProviderRuntimeRegressionTest版本断言3→4失败，已更新当前版本断言，不改旧SQL及历史。binding-package退出0；verify-cli-migration退出0：仅临时库，一致性备份、关闭重开、1002消息/501审计全量不变、零旧绑定、重复/伪schema拒绝及故障回滚通过。并发8次同元组单UUID单绑定，无孤儿；已知密钥身份零存储调用。

报告快照：.tools/lesson18/implementation/binding-green/counts.json；当前全量XML：{'suites': 96, 'tests': 351, 'failures': 0, 'errors': 0, 'skipped': 0}。只计本轮全量运行所覆盖报告。

### repl-green

repl-red-channel：2个逐行/安全行为失败；repl-red-mode首次仅格式失败不算行为RED，格式修复后repl-red-mode-formatted为1个TTY模式行为失败；repl-red-wiring为2个会话/quit行为失败。修复后repl-green及repl-package退出0，新增输出故障/敏感输入/审计故障零发送与关闭重开测试通过。macOS打包PTY/pipe/显式交互/quit/重开探针退出0，正文明确拒绝。Linux全量package退出0，新ReplWiringTest正文两处理/历史正向通过。Linux首次JAR探针因使用HTTP替身被生产HTTPS限制拒绝（保留FAIL）；改为临时CA的HTTPS回环，未改生产检查，repl-linux-probe-tls退出0。所有外部模型/天气调用仍NOT_RUN。新增进程harness为scripts/verify-cli-runtime.py，由verify-cli-package.py串行调用。

报告快照：.tools/lesson18/implementation/repl-green/counts.json；当前全量XML：{'suites': 99, 'tests': 360, 'failures': 0, 'errors': 0, 'skipped': 0}。只计本轮全量运行所覆盖报告。

### management-green

management-red：只读查询4例中3失败1错误；management-red-boot为CRUD/能力4个行为失败。实现后management-green/package/package-final退出0；打包management-final-probe退出0，含Profile增删不覆盖、摘要字段白名单/目录字节mtime零写、serve实际本机健康/占用拒绝/停止后chat重开、gateway6。Linux管理阶段package退出0。追加查询锁同JVM/跨进程及硬链接所有权保护回归通过；迁移脚本加完整Tool交换，migration-complete-exchange-report.json退出0，保存1006消息/503 LLM/1工具审计。独立复审发现后续修复条目，Linux首次全量quality因acquireQuery filename潜在空值SpotBugs失败，完整日志保留，不能计最终quality通过。

报告快照：.tools/lesson18/implementation/management-green/counts.json；当前全量XML：{'suites': 102, 'tests': 370, 'failures': 0, 'errors': 0, 'skipped': 0}。只计本轮全量运行所覆盖报告。

## 独立复审、追加修复与存档覆盖补强（T069～T075）

本轮使用 reviewing-code Deep：工作区相对 `edf49bf`，初始76文件，11维度（security、robustness、requirements-compliance、coding-style、maintainability、claude-md-compliance、shallow-bug-scan、historical-context、code-comments-check、database、dependency-version）。根目录无CLAUDE.md按规则跳过；历史只有8条提交，historical-context按少于10条规则跳过深历史。PDF仅指纹核验。初始完整性75文本/1二进制、遗漏0；收尾新增5文件，最终81文件的增量另行补审，不能用初始覆盖冒充最终。

初审报告3个新增候选，独立评分置信度均90，过滤0；原发现与评分保留，修复后单独记录关闭。技能内部分类为P0/P1（功能错误按其模型归类），实际原始影响优先级为报告覆盖P1、分页排序/帮助P2；两套标签不混为生产事故等级。

| 任务 | RED / 失败证据 | 最小修复与GREEN依据 |
| --- | --- | --- |
| T069 分页排序 | `review-red-java.log`：不同小数精度Instant按TEXT排序返回旧记录 | 连接级秒/纳秒纯函数排序，id稳定次序；ReadOnlySessionCatalogTest新增反例，既有零写/忙/旧库/WAL测试保持 |
| T070 报告不覆盖 | `review-red-report.log`：原脚本对既有哨兵报告路径exit0并覆盖 | `open('x')`独占新建；`review-green-report.log`及新增Python unittest检查原文件/软链接不变和新报告成功 |
| T071 帮助模型 | 最初`review-red-help-focused.log`是格式阻断，**不是行为RED**；已格式化测试临时还原旧模型后，`review-red-help-behavior.log`3例中1行为失败（delete错误出现batch） | 恢复完整修复源码，共享allowed清单、逐叶必填和chat Profile别名互斥；`review-green.log`与后续全量GREEN；独立新JAR12help逐项核对 |
| T072 查询锁静态检查 | `final-linux-quality.log`首次storage SpotBugs报filename潜在空值，整体FAIL | acquireQuery在打开前显式拒绝空文件名；所有权、既有sidecar和硬链接边界不变；最终双平台quality单列 |
| T073 全档重开覆盖 | 此项是补强覆盖，**不是声称旧实现有行为缺陷** | CliSessionPersistenceTest经生产Spring/Memory创建绑定、在规范V4临时库写1006消息/503 LLM/1Tool，关闭重开全表逐列投影一致；恢复窗口1000、末尾完整工具交换、绑定UUID不变、模型/工具发送均0。`final-archive-test.log`及最终全量XML通过 |
| T074 渠道封装静态检查 | `final-macos-quality.log`，2026-10-24 13:53：CLI EI_EXPOSE_REP2，exit1；不计最终通过 | ChatCommand只保存REPL受限读/写行为，沿用batch方式，不添加SpotBugs豁免；保留已有编码/输出/故障回归 |
| T075 Profile父路径静态检查 | `final-macos-quality-fixed.log`，13:56：CLI已通过，boot四项父路径空值告警，exit1 | 统一parent(Path)显式检查，固定安全拒绝；保留CREATE_NEW/NOFOLLOW_LINKS、批准根/原子移出和回归，无规则豁免；`final-macos-quality-final.log`13:58整体通过 |

所有日志根为 `.tools/lesson18/implementation/`（本地忽略，不入Git）；原始候选 `.reviewing-code-diffs/_result_*.json`、原评分 `_result_scored.json` 保留。最终独立增量复核结果分别写 `_result_006_database_final.json`、`_result_006_requirements_final.json`、`_result_006_final_scoring.json`；复核agent不执行Maven/真实外呼，构建由实施链路单独提供证据。

## 最终工程执行记录（截至依赖在线扫描启动，后续结果追加）

环境：macOS aarch64 / Azul21.0.9 / Maven3.9.14；Linux为OrbStack内隔离Ubuntu、OpenJDK21.0.12.1，使用本轮新源码副本且强制`aegisos.test.require-secure-directory-stream=true`。Maven/clean串行，未运行real-*。除依赖安全扫描外，本轮Maven门禁使用已缓存制品离线模式，不修改全局settings。

| 运行 / UTC开始时间 | 命令/范围 | 退出码 | 新鲜测试统计 | 结果与证据 |
| --- | --- | --- | --- | --- |
| final-macos-clean-package-final / 2026-10-24T05:57:19.147447+00:00 | `mvn -B clean package` | 0 | 102 suites / 373发现 / 0失败 / 0错误 / 0跳过 | PASS；`.tools/lesson18/implementation/final-macos-clean-package-final.log` |
| final-macos-quality-final / 2026-10-24T05:57:49.279900+00:00 | `bash scripts/check.sh quality` | 0 | 102 suites / 373发现 / 0失败 / 0错误 / 0跳过 | PASS；`.tools/lesson18/implementation/final-macos-quality-final.log` |
| final-macos-probe / 2026-10-24T05:58:38.618487+00:00 | `python3 scripts/verify-cli-package.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after 2026-10-24T05:57:19.147447+00:00 --allow-no-strace` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-macos-probe.log` |
| final-migration / 2026-10-24T05:59:09.302007+00:00 | `python3 scripts/verify-cli-migration.py --output /Volumes/Data/my_project/java-agent-os/.tools/lesson18/implementation/final-migration-report.json` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-migration.log` |
| final-migration-unittest / 2026-10-24T05:59:09.469117+00:00 | `python3 -m unittest discover -s scripts/tests -p test_verify_cli_migration.py` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-migration-unittest.log` |
| final-secrets / 2026-10-24T05:59:09.940650+00:00 | `bash scripts/check.sh secrets` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-secrets.log` |
| final-dependencies / 2026-10-24T05:59:23.606601+00:00 | `bash scripts/check.sh dependencies；Keychain环境注入，首次错误使用离线模式` | 1 | 不适用；非JUnit门禁 | FAIL：插件要求online；旧本地helper外码0不可靠，内层command.json真实码1。未复用任何残留报告；`.tools/lesson18/implementation/final-dependencies.log` |
| final-linux-quality-fixed / 2026-10-24T05:59:49.508139+00:00 | `python3 .tools/us2-linux/gate.py clean verify -Pstatic-analysis` | 0 | 102 suites / 373发现 / 0失败 / 0错误 / 0跳过 | PASS；`.tools/lesson18/implementation/final-linux-quality-fixed.log` |
| final-linux-probe / 2026-10-24T06:00:45.668620+00:00 | `/Applications/OrbStack.app/Contents/MacOS/bin/orbctl run -m aegisos-us2-tests -u root -w /tmp/aegisos-us2-workspace python3 scripts/verify-cli-package.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after 2026-10-24T05:59:49.508139+00:00` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-linux-probe.log` |
| final-linux-migration / 2026-10-24T06:01:17.221698+00:00 | `/Applications/OrbStack.app/Contents/MacOS/bin/orbctl run -m aegisos-us2-tests -u root -w /tmp/aegisos-us2-workspace python3 scripts/verify-cli-migration.py` | 0 | 不适用；非JUnit门禁 | PASS；`.tools/lesson18/implementation/final-linux-migration.log` |

macOS JAR：12帮助/元数据/init不覆盖不建库、CRUD/只读目录字节和mtime零写、serve健康→锁忙→停止→chat重开、gateway6、pipe/batch/PTY均通过；正文SDS拒绝通过。无strace，`NETWORK_SYSCALL_PROOF=NOT_RUN`。

Linux JAR：同会话两次处理/审计/历史正向、真实PTY默认、pipe/显式模式、quit无额外处理、绑定重开、CRUD/只读零写/serve停机重开/gateway均通过；strace init零IP connect/send/bind/listen通过。由脚本断言，不仅是stdout非空。

迁移及生产全档重开：1006消息、503模型审计、1工具审计、完整Tool交换及逐列投影保留；旧UUID无绑定、重复迁移/伪schema拒绝、故障回滚通过。Python迁移报告出口unittest发现1例、0失败/错误；Gitleaks8.30.1历史与工作树均no leaks。

各成功全量XML复制至同名证据目录及counts.json。首次质量失败不汇总其残留XML。线上NVD受控扫描结果尚未完成，依赖安全门禁当前不放行；没有以旧扫描代替。

### 真实与交付范围

| 项目 | 当前结果 |
| --- | --- |
| 第18节真实天气/真实模型 | NOT_RUN，本轮无授权；旧US2真实记录不继承 |
| 个人库迁移/生产部署 | NOT_RUN；只操作自动临时库 |
| sessions.messages_json | 批准保留V3分表的偏差，不存在该实列 |
| 完整Web业务/多通道/三模式对话 | 延期；serve仅Web基础，gateway明确退出6 |
| commit/push | 未执行；HEAD仍edf49bf，分支codex/006-cli-course-alignment |

原PDF SHA256及暂存blob仍与本文件基线一致，002/003/005任务与验证、全部旧SQL均无diff，无target进入Git。初步最终文档检查15份Markdown/117本地链接/75连续唯一任务通过；最终文档复核与门禁结论在后续追加。

## 依赖安全门禁最终结果（2026-10-24 14:07，T062/T072）

最终 `bash scripts/check.sh dependencies` 退出0。实际命令通过受控Keychain仅向子进程环境注入NVD凭据，在线Maven使用临时Central设置，未修改全局settings、POM阈值或分析器开关。内层command.json与独立监督进程均核对为0，**没有把旧helper固定外码0当作成功**。

- 本轮依赖报告：Dependency-Check13.0.0，104项依赖，包含picocli-4.7.7.jar，漏洞记录0；当前配置CVSS>=7阻断及failOnError仍开启。
- 新鲜证据：`.tools/lesson18/implementation/final-dependencies-refreshed/`，含本轮command.json、完整日志、HTML/JSON；安全摘要`final-dependency-summary.json`。原路径`.tools/us1-evidence/authenticated-20261024-140707/`仍保留。
- 首次FAIL：离线模式被插件拒绝，内层1；第二次FAIL：NVD已更新7094/7094，但RetireJS官方raw域名在本机DNS为0.0.0.0，连接拒绝。两次FAIL、直接官方IP请求超时、初次API20秒仅收到部分响应均保留，部分数据没有使用。
- 修复环境后重验：通过Retirejs/retire.js官方GitHub Contents API、正常TLS校验下载完整587246字节；解码后Git blob SHA为`28cfb8c7e3392d71e2cc9bfad383a29ce956adf5`，与API返回一致；SHA256为`01756a592875f31e99481ace7a05a9c23e6915027c719495d542709db7ee2c35`。仅刷新插件本地RetireJS缓存及真实获取时间，旧缓存先备份。来源/指纹见`retirejs-cache-provenance.json`。
- 原RetireJS分析器继续启用，缓存有效期未改；NVD自动更新继续开启。最终日志BUILD SUCCESS；未采用关闭分析器、离线旧报告或压低漏洞阈值的方式放行。
- OSS Index因未配置其独立Token按插件默认行为未运行（本轮日志有明确警告），此项不等于完整厂商数据源覆盖；当前约定的NVD/RetireJS及安全门禁通过。远程CI尚未运行，当前为本机和隔离Linux执行证据。

截至此时：macOS最终clean package及quality、Linux全量quality均102 suites / 373测试 / 0失败 / 0错误 / 0跳过；双平台新JAR/临时迁移、Gitleaks、当前依赖扫描均通过。独立源码复审三项原问题已关闭；最后文档一致性与任务收尾由独立复核另记。

## 最终收尾结论（2026-10-24T06:12:50.687761+00:00，T066/T068）

- 独立最终复核：`_result_006_requirements_final.json`与`_result_006_final_scoring.json`均COMPLETE，原3项修复关闭、新增可行动问题0；数据库独立增量结果同为无剩余问题。review agent只做静态核对/既有报告核验，未把实施者自检写成独立执行。
- 最终Review报告：`.reviewing-code-diffs/_report.md`；初始76文件与最终81文件索引、原评分/关闭证据保留。新增5文件补审齐全。关闭后的任务勾选、规格状态及本段是主Agent消费独立结论后的纯文档变化，单独记录closeout delta；Java/POM/SQL/脚本无再改动。
- 最终密钥复扫：`bash scripts/check.sh secrets`，2026-10-24T06:09:33Z～06:09:47Z，exit0，Gitleaks8.30.1历史与当前工作树均no leaks；证据`final-secrets-after-docs.log`。
- 本轮完成原68项工程/记录任务及追加T069～T075，共75/75；T064是授权与NOT_RUN状态记录，不是完成真实天气。当前无剩余工程阻断，但本节真实模型/天气尚未执行，不能宣称完整课程外部验收通过。
- 变更总结/审查/复跑指南已进入README，全部本轮Markdown本地链接、代码围栏、任务连续性及原PDF/暂存blob/旧历史/旧SQL保留检查通过，最终核验报告`final-document-check.json`。
- Java21、九模块、Boot4.1.1/Spring AI2.0.1基线不变；没有target跟踪、个人库迁移、远程CI、部署、commit/push。当前HEAD仍edf49bf，工作分支codex/006-cli-course-alignment。

接手时先读 [CLI使用指南](../../docs/engineering/cli-usage.md)，再读 [代码变更、重点Review与验证](../../docs/engineering/006-cli-course-alignment-changes-review-verification.md)。下一步若做真实天气，须另行明确本轮授权、run-id、模型/GET次数预算和受控凭据注入；未获得授权前保持关闭。

## 第18节真实天气授权与准备（2026-10-24，T076）

用户明确要求“真实天气尚未执行，要执行”，本轮执行已获授权，无需再次索要执行许可。此前NOT_RUN记录保留为历史；当前状态为**已授权、缺受控凭据、尚未外呼**。

- 新run-id：`cli18-20261024-062003-15860d17`；专用Linux临时父目录`/tmp/aegisos-cli18-real`，未打开个人库。配置指纹SHA256：`9f141bfe8bdeaa766788dfd80bda6696a783494131a1f22919169c40a91eb18b`。
- 目标与预算：DeepSeek `deepseek-flash`、`thinking-mode: disabled`、Provider重试0；上海31.23/121.47的Open-Meteo HTTPS，只允许批准路径和查询字段；同会话两条消息和quit，最多3轮/处理、6次模型发送、2次天气GET，外呼前扣预算，失败不自动重跑。
- 准备入口：`.tools/lesson18/real-weather/`（忽略目录），保留独立YAML、源码快照、`CliWeatherAcceptance.java`、`run.py`与`preflight.log`。复用003的预算与天气语义断言；使用生产Picocli解析、CLI Bean工厂、ReplChannel、ChatCommand及Agent/Provider/Sandbox/存储，仅在调用端口增加预算与观察。不把此入口称为打包JAR进程验收；新JAR的PTY/pipe验证仍引用此前独立回环探针。
- 已执行：核对294份Java源码与Linux已验证工作区一致；Linux `javac` 编译验收入口退出0；`CliWeatherAcceptance --preflight`退出0，SDS及配置检查通过。Python入口语法检查通过，缺凭据时固定拒绝，未创建`attempt-started.json`，未创建运行新库或产生真实执行结果。
- 凭据检查仅输出可用布尔值：本进程和Linux环境的`DEEPSEEK_API_KEY`均为空，常用DeepSeek专用Keychain服务名未找到。已询问受控存储位置/注入方式；没有将聊天历史中的密钥写入脚本、YAML、日志或命令。
- 当前模型发送0、天气GET0；这只证明预检无外呼，**不代表真实验收PASS**。真实执行、天气/审计与重开断言仍待取得受控凭据后完成。生产Java/POM/旧历史未改，无commit/push。

## 第18节真实CLI天气执行与独立回读（2026-10-24，T076 PASS）

本段取代上一段的“等待凭据”当前状态，原记录保留为历史。用户提供本轮凭据后，经隐藏输入仅注入验收子进程环境；未写入YAML、脚本、参数、日志、报告或Git。未再询问执行许可，未重跑真实外呼。

| 项目 | 本轮证据与结论 |
| --- | --- |
| 执行编号/时间 | `cli18-20261024-062003-15860d17`；2026-10-24 14:25:05～14:25:13（Asia/Shanghai）；命令退出0 |
| 环境/配置 | Linux临时V4新库，DeepSeek `deepseek-flash`、显式`thinking-mode: disabled`，Provider重试0；配置指纹与预检一致 |
| 真实调用 | 3次逻辑请求=3次物理模型发送；1次Open-Meteo GET；预算6次模型/2次GET内，前置额度检查生效 |
| CLI行为 | 生产Picocli解析及交互命令，两条天气消息均COMPLETED；轮次分别2、1；同一UUID会话与`(cli,local,weather)`绑定；CLI退出0 |
| 天气事实 | 上海31.23/121.47、Asia/Shanghai；Open-Meteo观察时间2026-10-24 14:15，气温20.3°C，weather_code=55，风速16.8 km/h；新鲜度、坐标、时区和单位检查通过 |
| 回复/续聊 | 两次回复均给出20.3°C并建议携带雨具、薄外套、注意湿滑；运行内断言实际发送的TOOL消息与捕获天气结果一致，第二条沿用同会话事实；独立回读再次核验气温及建议 |
| 审计与存档 | 关闭Spring后新连接回读：1会话、1绑定、2处理、3模型审计、1工具审计、6条消息；模型request/process/iteration/session及工具观察关联、成功状态、attempt_no=1均通过 |
| quit/重开 | `/quit`不作为用户消息保存；重开整个运行时后仍绑定原UUID，quit无额外模型/工具调用；最终只读复核数据库字节SHA256不变，完整性与外键检查通过 |

本轮证据根为`.tools/lesson18/real-weather/`（本地忽略）：`attempt-started.json`为排他执行标记；`command.json`记录真实退出0；`result.json`保存运行内断言与计数；`verification.json`保存独立Python只读事实/关联/完整性复核，退出0；`execution.log`为脱敏运行日志。独立复核脚本没有模型凭据、没有外呼。

- 结果JSON SHA256：`6bf47fe8c032c24e6c4abc8c6b3b14dee99a3f4419472795268908b9a1440fd8`；数据库SHA256：`a86338655feab3ccb05cfbbda7546cb7bf8ec681acf3fb1f499a19d31ce7d887`。
- 保留首次独立复核FAIL：脚本误将`PRAGMA user_version`作为工程版本字段；源码实际使用`aegisos_schema`。改为断言`(id=1,version=4)`后只重跑只读复核，退出0；没有重跑模型/天气请求，记录`verification-first-failure.json`。
- 验收证据目录单独Gitleaks复扫退出0，无泄露。此前错误指定不存在配置文件的失败、SHA256清单误报均在`scan-history.json`保留；仅将含Credential/Secret文件名与SHA256同一行的清单改成分行path/sha256结构，原始清单与redacted报告保留在父目录，未改规则/加豁免。最终报告`.tools/lesson18/real-weather-secrets-final.json`。
- 验证边界：本次为带前置预算及观察端口的生产CLI交互命令验收，不宣称另跑了真实打包JAR天气进程；JAR/PTY/pipe层使用本节已有回环探针结果。没有新增生产Java/POM/依赖，不无理由重跑373项Maven门禁；当前继续引用已验证工程基线，真实验收不计入JUnit373项数量。
- 当前T001～T076为76/76；第18节真实CLI天气已PASS。完整Web业务/多渠道/三模式仍延期，个人库迁移、远程CI、生产部署及commit/push均未执行；002/003/005验证历史不变。

### 本次真实验收收尾检查

- `bash scripts/check.sh secrets`最终退出0：历史9个提交与当前工作树均无泄露，日志`.tools/lesson18/real-weather-worktree-secrets-final.log`。首次全树扫描的6条候选全部为文件SHA256（原指纹清单5条、既有复审证据指纹1条），已逐项核实；只将两个本地JSON的哈希值排到独立行，JSON语义/复审结论不变。转换前原始字节与SHA256保存在`.tools/lesson18/real-weather-scanner-format-backups.json`，原失败日志/报告保留，未加规则豁免。
- 最后文档检查：12份Markdown、124个本地链接、76/76连续唯一任务通过；首次轻量检查器未解码链接的`%20`，误报原有PDF缺失，修正检查器后通过，没有修改有效链接。记录`closeout-check.json`。
- 294份Java源码与真实运行前快照逐一SHA256相等；SQL/Provider旧验证历史没有本轮变化，没有target被跟踪。另以只读连接确认风速单位km/h、模型/工具审计全部成功，并检查临时数据库无`sk-`密钥模式；不读取或修改个人数据库。

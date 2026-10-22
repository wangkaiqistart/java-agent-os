# 验收契约：课程追溯、测试发现和证据

**状态**：本地入口已实现并验证，实际结果见 validation.md；指定DeepSeek模型已于2026-10-22单次真实验收PASS；最终任务及安全门禁见validation.md。

## 默认测试与真实测试隔离

普通测试继续使用 Surefire 3.5.2、本地 loopback HTTP stub、临时文件和 SQLite。默认启动、test、package、quality/CI 即使环境有 DeepSeek 凭据也不调用真实网络；不以“没配 key”代替默认零外呼测试。

真实测试固定为 `com.aegisos.boot.provider.ProviderSmokeIT` 的一个 `@Test`，标签 real-provider。仅 boot 的 real-provider-smoke Maven profile 启用 Failsafe 3.5.2：绑定 integration-test、verify，includes 精确包含该类，groups=real-provider，failIfNoTests=true、failIfNoSpecifiedTests=true，并向测试 fork 传入布尔启用标记。配置不转发环境凭据为 JVM 属性，不让 key 出现在 Maven 命令、XML 系统属性或失败文本。

默认 Surefire 不包含 *IT；默认 CI 不启用冒烟 profile。不得在 root reactor 全局设置“所有模块必须有指定测试”，也不得用全局关闭发现门槛掩盖 boot 缺类。配置遗漏/跳过均需由报告核验器发现。

## 冒烟执行链

1. 显式 profile 启用后，预检 YAML、Profile 名、调用方 sessionId、DEEPSEEK_API_KEY 是否非空及重试为 0。缺条件安全终止，不使用 Assumptions。配置从专用 YAML 读取，临时工作区复制 Agent Profile 夹具；凭据仅由子进程继承受控环境。
2. 采用现 BootProviderFixture 风格的最小生产配置装配：ProviderConfiguration、AuditStorageConfiguration、ProfileConfiguration；不启动 Web/调度/完整应用，不使用测试替身替换实际模型适配器。
3. 预检要求 DeepSeek、显式获准模型、空 tools、temperature/maxOutputTokens 均省略。一次调用新 chatProfile(sessionId, profileName, prompt)，非敏感固定提示，禁业务/SDK/传输重试和重定向。
4. 传输层在 OkHttp 实际网络交换点提供只计数的观察器，记录本次 HTTP 发送尝试，不记录 URL、头或正文。不用 adapter 次数/审计条数冒充 HTTP 实发数；本地 stub 验证该观察器对重定向、503、连接故障等无隐藏重发。成功验收要求该次真实网络交换计数为 1，不宣称计数证明上游内部计费/去重。
5. 响应文本非空；隔离 SQLite 新增恰好 1 行，session_id 等于显式输入、request_id 等于响应、attempt_no=1、status=SUCCEEDED、success=true、error_code/error_message 均 null；重开数据库再次确认。token 用量仅验证存在时合法，不要求补零或厂商必返。
6. 生成只含安全计数、固定身份、requestId、非空响应布尔及审计断言布尔的 JSON 证据，保存时间/runId 和配置指纹（排除秘密值，不对密钥求摘要）。不保存完整回复/提示/原始异常/认证信息，不上传临时数据库或凭据。

保持现有 `/v1` 路径契约；可能的厂商不兼容必须真实 FAIL，不能自动换成另一 URL/模型补跑。省略 token 上限不是计费上限，实际调用前明确 [研究记录](../research.md)中的成本边界；真实调用前须明确授权；本轮已按单次授权执行，结果见validation.md。

## 报告核验与分类

已提供标准库脚本 `scripts/verify-provider-smoke.py`；只读取本次 boot Failsafe XML 和安全 JSON，不访问环境/凭据/数据库。真实流程先 clean 排除旧报告，并核对 runId/时间对应；不得从旧报告恢复 PASS。核验器本身需要合成报告用例。

| 实际条件 | 结果 | 命令/课程状态 |
| --- | --- | --- |
| 未启用、缺配置/凭据、目标未发现、被跳过或未实际执行 | NOT_RUN | 显式验收非成功退出；课程未完成 |
| 已进入测试后发现配置不可用且尚未模型调用 | NOT_RUN，安全原因 | 测试以安全断言失败退出；不可作为 PASS |
| 已具备条件但网络/认证/响应/审计/发送计数不符合 | FAIL | 非成功退出；不得自动重跑 |
| 缺证据、报告格式错、报告互相矛盾 | 无执行证据为 NOT_RUN；已有执行证据但断言不符为 FAIL | 均非成功退出 |
| 本次发现=1、执行=1、skipped=0、failures/errors=0，且全部真实/审计断言满足 | PASS | 仅此条件可完成 D-02 |

Maven 成功但核验失败不能放行；Maven 失败不能被核验器改写成功。test 方法数=1 与报告中因发现缺失而 tests=0 严格区分。配置未满足而断言失败的 XML 由安全 JSON 的阶段分类为 NOT_RUN，不能把“不运行”算网络失败或成功。

## PDF → FR/SC → 场景 → 测试

实际验证记录还需填任务 ID、命令、时间、统计、结果和脱敏证据路径；本表不提供实际 PASS。

| PDF 条目 / 页码 | FR | SC | 场景 | 测试类 / 重点 |
| --- | --- | --- | --- | --- |
| PDF16-01 / 3～4、6～7 | 001、002、003、005、006 | 001、002、010 | A-01、A-02、A-03、A-05 | ProfileLoaderTest、ProfilePathSafetyTest、ProfileSnapshotTest：字段、坏文件、重名、环境、快照及技能来源 |
| PDF16-02 / 4 | 004、005、006、007 | 002、008、010 | A-03、A-04、A-05、A-06 | ProfileRoutingTest、ApplicationYamlCompatibilityTest；复用 ProviderConfigurationTest/ProviderSnapshotTest |
| PDF16-03 / 5 | 008、009 | 003 | B-01、B-02、B-06 | ProviderSessionTest：两会话、多请求、重试和未关联兼容 |
| PDF16-04 / 5～8 | 010、011、012、019 | 004、009、010 | B-03、B-04、B-05、B-06 | JpaLlmCallAuditStoreTest 扩充、AuditSchemaMigrationTest、ProviderAuditFailureTest 扩充 |
| PDF16-05 / 7～8 | 013、014 | 005 | C-01、C-02、C-03 | ToolSchemaContractTest、OpenAiCompatibleAdapterTest 扩充：最终 JSON、递归拒绝、零执行/零第二轮 |
| PDF16-06 / 6、8 | 015、016、017 | 006、007、010 | D-01、D-02、D-03 | DefaultProviderIsolationTest、ProviderSmokeEvidenceTest（均本地）；ProviderSmokeIT（真实独立启用） |
| PDF16-07 / 2、6～8 | 004、012、014、018、019、020、021、023 | 002、005、007、008、009、010 | A-04、B-02、B-05、C-03、E-01、E-02 | 原 US-1 全部测试及本轮新增集成；质量/秘密/依赖门禁；证据与历史保护检查 |
| 用户交付约束 | 007、020、021、022、023 | 007、008 | A-06、E-01、E-02 | YAML 兼容测试、中文注释/追溯人工检查、新证据、旧文件保护 |

本表 FR/SC 数字均指本功能对应的 FR-xxx/SC-xxx，覆盖 23 项 FR 和 10 项 SC。

## 20 个场景的独立验证边界

| 场景 | 模块 / 测试 | 核心断言 |
| --- | --- | --- |
| A-01 | boot / ProfileLoaderTest | 全字段与嵌套结构、目录技能、通知引用；缺省可选字段；元数据加载零执行 |
| A-02 | boot / ProfileLoaderTest、ProfilePathSafetyTest | 混合合法/坏 YAML/未知 Provider/重名；全部重名隔离、无覆盖；安全诊断 |
| A-03 | boot / ProfileEnvironmentTest、ProviderSecurityTest 扩充 | 存在/缺失/空/非法 env；只在三字段展开；秘密哨兵零泄漏 |
| A-04 | boot / ProfileRoutingTest | 两 Provider HTTP stub；模型/温度一致；缺名/缺模型/越界零发送；Profile 拒绝经统一审计 |
| A-05 | boot / ProfileSnapshotTest | 文件/环境/新增/删除修改后旧 context 不变，新 context 才变化；重试快照不混用 |
| A-06 | boot / ApplicationYamlCompatibilityTest | 默认/dev/metrics/test 的有效绑定保持；无同名 properties 覆盖；日志安全关闭 |
| B-01 | boot / ProviderSessionTest | 两会话、同会话多请求成功/失败真实 SQLite 存读及唯一性 |
| B-02 | provider、boot / ProviderRetryTest 扩充、ProviderSessionTest | N=2 最多 3，失败→成功记录都保留同关联；默认 0；无 SDK 重发 |
| B-03 | storage / JpaLlmCallAuditStoreTest 扩充 | 四状态真实列、null/布尔及安全说明；原子完成/约束反例；未知用量 |
| B-04 | storage / AuditSchemaMigrationTest | 原 V001 四状态+大用量/未知错误；升级/重开/新写；失败回滚/版本拒绝 |
| B-05 | provider、storage / ProviderAuditFailureTest、AuditStorageFailureTest 扩充 | 各次 begin 提交前零发送；complete 故障停止重试、UNKNOWN；此前事实保留 |
| B-06 | boot / ProviderSessionTest | null/空白/非法拒绝且原值不入库；合法未知会话接受；旧入口 null；无会话查表 |
| C-01 | provider / ToolSchemaContractTest | 最终 HTTP 名/描述/类型/required/嵌套/items/enum 语义树一致 |
| C-02 | provider / ToolSchemaContractTest | 嵌套 $ref、错误 required、非法 items、未知约束等零发送且 REJECTED |
| C-03 | provider / OpenAiCompatibleAdapterTest 扩充 | 纯意图、混合多个意图，文本/顺序/id/参数保留；执行=0、HTTP=1、无第二轮 |
| D-01 | boot / DefaultProviderIsolationTest | 即使凭据存在，启动/默认测试零真实外呼；默认发现列表不含 IT；未启用 profile 的有效 POM 无 Failsafe 执行 |
| D-02 | boot / ProviderSmokeIT | 实际执行 1、发送 1、非空文本、1 条关联成功审计、重开可读 |
| D-03 | boot / ProviderSmokeEvidenceTest、报告核验器合成用例 | 缺条件/零发现/跳过/旧报告不能 PASS；失败分类准确，无自动重跑 |
| E-01 | 各受影响模块 / 原全部测试+新增测试 | 行为回归与每故事 reactor test/package；安全门禁保留；失败阻断依赖故事 |
| E-02 | 文档/人工评审+秘密扫描 | 7 条完整证据、批准替代、详细中文注释、原 51 项/历史未覆盖 |

路径软连接、YAML 资源限制、秘密异常、数据库 CHECK 的测试均需包含会失败的反例；不要只测试 DTO getter 或复制实现常量。先运行预期失败的行为测试，再实现、再通过；tasks 阶段写出依赖及命令。

## 增量门禁和记录

每故事独立验证使用 [quickstart](../quickstart.md)中的聚焦选择；随后 `mvn test`、`mvn clean package`。B 基础未通过不得开始依赖它的 A 集成；A/B/C 未通过不得开始真实 D。E 在每步记录证据，最终执行 quality、secrets、dependencies 并人工核对中文注释。

已写 `specs/005-provider-course-alignment/validation.md`，证据可链接本地脱敏报告或受控 CI artifact。逐条记录：PDF/页码、FR/SC、场景/任务/测试、批准替代、实际命令、开始结束时间、退出码、发现/执行/跳过/失败统计、发送次数、审计断言、PASS/FAIL/NOT_RUN、证据路径。替代项明确 skills 目录来源、通知名称引用、独立 YAML、旧入口未关联、当前版本框架 API 等差异。

原 specs/002 的 tasks.md/validation.md 及 V001 不覆盖；原测试保持并补充，不能删掉困难场景以通过。旧 91 项/NVD 结果不证明本轮安全或课程完成。任意未达标项如实保留，禁止把本地 stub、构建成功或 NVD 扫描替代真实模型验收。

实现核对：Failsafe 3.5.2 的成功 summary.result 实际序列化为字符串 null（隔离零网络探针执行1测试确认）；核验器同时严格检查 XML根、时间、runId、测试身份及全部计数/布尔。外层必须保留 Maven退出码，可用可选参数 --maven-exit-code 额外阻止任何非零结果被报告覆盖。此为既定契约的格式落实，不改变发现/单次调用/失败分类语义。

实现补充：boot的Failsafe显式使用`${project.build.outputDirectory}`，避免Boot重打包JAR造成应用类不可见；IT先验证应用类路径，类加载故障为FAIL/SETUP，不能误报缺凭据。Failsafe 3.5.2的真实testsuite可不含timestamp，此时核验报告文件mtime与JSON起止窗口；runId、唯一类/方法、全部统计和Maven退出码仍必须满足。预检缺条件仅在XML无运行错误且发送0时为NOT_RUN。

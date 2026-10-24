# US2 验收与证据契约

**当前状态**：A01～A13离线范围、A15架构复核、A16显式模式和完整工程门禁均PASS；用户新授权的A路线A14真实天气也已PASS（3模型发送/1GET、同会话两处理完成、零重试）。首次真实FAIL保留，见 [validation.md](../validation.md)。默认构建不访问外部真实服务，后续真实运行仍须明确启用。

## 1. 按故事与课程测试分组

| 验收组 | 故事 / 计划测试 | 行为与观测证据 |
| --- | --- | --- |
| A01 | 故事1；AgentServiceTest、SessionPersistenceTest | 单响应文本结束；有序保存；session隔离/错归属/未知拒绝；存储关闭重开与新运行时恢复 |
| A02 | 故事1/3；AgentServiceTest、RuntimeRecoveryTest | 同会话竞争恰一执行；DB进程锁；崩溃/保存失败；遗留RUNNING中断、OPEN组排除、零重放；finally清理 |
| A03 | 故事2；ToolExecutorTest、ToolDeduplicationTest | 单/多/多轮工具、串行顺序、可靠失败回填、重复复用/冲突、同响应重复先拒绝、wire ID兼容 |
| A04 | 故事2；ToolAuditPersistenceTest | begin提交前零副作用；complete或观察保存失败停止；工具状态/success/error_message一致，重开可查 |
| A05 | 故事2；HttpGetToolTest、SandboxPolicyTest | exact授权、DNS固定、公私混合/地址绕过/重定向拒绝、请求计数、超时/大小/资源、零重发 |
| A06 | 故事3；ReActLoopTest | N=1/2/10精确计数、末轮工具0、无N+1；文本+工具保留；无工具空响应错误 |
| A07 | 故事3；ProcessingCorrelationTest、Provider既有回归 | request/attempt/process/session关联在外呼前提交；无外层重试/换路由；模型传输容量；真实已发生审计不篡改 |
| A08 | 故事4；PromptBuilderTest、MemoryServiceTest | 组成顺序、Clock/时区、授权schema、默认空长期记忆/替身；统一Memory门面 |
| A09 | 故事4；ContextLoaderTest、ProfileSourceMappingTest | 来源名≠目录名；逐次读正文；绑定快照；缺失/WARN；路径替换/软连接/非法UTF-8/大小 |
| A10 | 故事4；PromptBuilderTest、PromptProjectionContractTest | 20完整轮次；中文/转义计量；必需超限零外呼；完整组裁剪；持久历史不删；wire配对合法 |
| A11 | 故事1～5；SensitiveContentGuardTest、LongTermContextTest与边界集成 | 合成密钥覆盖所有入口（包括可选长期记忆）；命中即结束当前处理且无保存/转发/输出泄漏，不能移除敏感片段后继续；普通描述/环境引用不误拒绝 |
| A12 | 故事5；CliCommandTest、CliConversationTest、CliRuntimeWiringTest | init不覆盖/不启库/不外呼；stdin边界；新建/续聊与退出码；非Web启动零监听端口 |
| A13 | 故事1～5；Reactor / schema / 验证器测试 | 九模块DAG、V3迁移回滚、旧Provider回归、默认零真实请求；验证器缺条件/陈旧/零发现等反例 |
| A14 | 故事5；ReactWeatherSmokeIT（仅显式启用） | 真模型→真http_get→天气观察→建议→同会话追问；审计重开与预算，见下文 |
| A15 | 人工代码评审与本表 | 自实现循环、SDK零自动工具、Memory门面、唯一Sandbox、无隐式外呼/秘密；逐项课程追溯 |
| A16 | A路线增量；ProviderThinkingModeTest、CliRuntimeWiringTest、ReactSmokeEvidenceTest、Python验证器反例 | YAML显式disabled→启动快照→首轮及工具续轮真实wire扩展；缺省无扩展且名称无推断；非法值零凭据解析/外呼；专用配置强制disabled与指纹变更 |

课程五组测试名按行为保留：core 的 ReActLoopTest、PromptBuilderTest、AgentServiceTest、ContextLoaderTest，tool 的 ToolExecutorTest；boot 增加跨模块/CLI集成，storage 验证真实事务与重开。不能用纯 mock 替代存储提交、恢复和生产装配测试。

每故事先完成其新增/受影响测试与适用 Maven test/package，才开始依赖故事。故事1提供会话/编排基础；故事2提供工具；故事3在1/2基础收敛上限和恢复；故事4的上下文安全基础应先于任何真实调用完成，可先开发其公共边界；故事5最后装配。最终再统一执行 `scripts/check.sh quality|secrets|dependencies`，不降低门槛或改写002/005验证历史。

既有测试保留业务断言；V1→V2迁移夹具与原脚本保留，增加V2→V3测试。原先“运行时最新schema=V2”的断言需调整为V3及明确旧库MIGRATION_REQUIRED，不能通过删除旧状态约束/断言让测试变绿。真实旧Provider冒烟仍是独立profile/单次判据，默认构建不运行。

## 2. FR 全覆盖

批准增量FR-039→A16→SC-012；该映射不改写原FR-001～038/SC-001～011及A14失败历史。

| 需求 | 验收组 | 契约入口 |
| --- | --- | --- |
| FR-001 | A01/A02/A06 | agent-memory-provider.md |
| FR-002 | A01/A03/A07 | agent-memory-provider.md |
| FR-003 | A01/A02/A04 | 数据模型/Memory事务 |
| FR-004 | A01/A06 | 循环顺序 |
| FR-005 | A03/A06 | tools-sandbox.md |
| FR-006 | A03/A04 | 结构化观察 |
| FR-007 | A06 | 末轮停止 |
| FR-008 | A03/A04/A07 | 失败分类与门槛 |
| FR-009 | A07/A15 | Provider零自动工具 |
| FR-010 | A04/A07/A11 | processing_events/审计关联 |
| FR-011 | A10 | context.md |
| FR-012 | A01～A13 | 默认离线测试 |
| FR-013 | A13/A14/A15 | 故事及工程门禁 |
| FR-014 | A01/A02 | 会话归属/恢复 |
| FR-015 | A08 | 确定性组装 |
| FR-016 | A08 | 时钟/schema |
| FR-017 | A09 | 正文重读/快照 |
| FR-018 | A09 | 缺失/故障区分 |
| FR-019 | A09/A11 | 路径/大小/安全读取 |
| FR-020 | A03/A05/A08 | schema过滤与重新授权 |
| FR-021 | A03/A05/A14 | 统一契约与http_get |
| FR-022 | A05 | 地址/方法/禁重定向 |
| FR-023 | A05/A10 | 资源与容量 |
| FR-024 | A04 | 工具真实表列与重开 |
| FR-025 | A04 | 提交前后故障 |
| FR-026 | A02 | ProfileContext清理 |
| FR-027 | A08/A15 | 统一Memory/空长期端口 |
| FR-028 | A01/A02/A06 | 状态/并发/持久确认 |
| FR-029 | A02 | 重启中断/OPEN组排除 |
| FR-030 | A10 | 20用户处理轮次 |
| FR-031 | A10 | 必填UTF-8预算 |
| FR-032 | A03/A10 | 原始去重与wire投影 |
| FR-033 | A11 | 拒绝敏感内容 |
| FR-034 | A07 | 公共Profile入口/显式关联 |
| FR-035 | A12 | configuration-cli.md |
| FR-036 | A13/A14 | 真实开关与判定 |
| FR-037 | A14 | 天气链路与审计 |
| FR-038 | A13/A14 | 隔离/执行前预算 |
| FR-039 | A16 | 显式thinking-mode、启动快照与配置指纹 |

SC-001→A01；SC-002→A03/A05/A15；SC-003→A06；SC-004→A02/A03/A04/A07；SC-005→A11/A13；SC-006→A01/A02；SC-007→A08/A09；SC-008→A10；SC-009→A02；SC-010→A12/A14；SC-011→A13/A14/A15；SC-012→A16。PDF17映射见 [课程追溯](../course-traceability.md)。

## 3. 默认关闭的真实天气验收

已实现 boot Maven profile `real-react-smoke`，Failsafe仅发现 `ReactWeatherSmokeIT`、tag `real-react`，classesDirectory保持 `${project.build.outputDirectory}`；报告使用独立 `target/react-smoke-failsafe-reports`，结果为 `target/react-smoke-result.json`，不能与旧Provider报告混用。普通 test/package/quality不启用真实profile，也不执行真实预探测。

专用 YAML 由 `AEGISOS_REACT_SMOKE_CONFIG` 指向，至少含：run-id、专用临时工作区父目录、provider-name/model（DeepSeek精确值）、credential-ref环境引用、批准天气目标、城市坐标、所需天气字段、时区、两条固定用户消息和预算。复用全局/Agent契约生成本次临时配置；不读个人库、仓库实际 `.aegisos` 或自动迁移。

专用配置结构示例（此处声明目标表示操作者明确批准；不由普通init默认填入）：

```yaml
run-id: react-20261022-example
workspace-parent: /绝对路径/隔离验收目录
provider:
  name: deepseek-main
  model: deepseek-flash
  base-url: https://api.deepseek.com
  credential-ref: env:DEEPSEEK_API_KEY
  thinking-mode: disabled
weather:
  city: 上海
  latitude: 31.23
  longitude: 121.47
  zone: Asia/Shanghai
  target:
    scheme: https
    host: api.open-meteo.com
    port: 443
    method: GET
    paths: [/v1/forecast]
    query-keys: [latitude, longitude, current, timezone]
  current-fields: [temperature_2m, weather_code, wind_speed_10m]
messages:
  - 查询上海当前天气，必须调用工具。按“气温：数值 单位；建议：具体建议”回答，气温只写一个值，四舍五入保留一位小数，单位使用摄氏单位。
  - 沿用本会话刚查到的天气事实，按“气温：数值 单位；建议：出门注意事项”回答，气温只写一个值，四舍五入保留一位小数，单位使用摄氏单位。
budgets:
  max-iterations-per-processing: 3
  provider-max-retries: 0
  max-model-requests: 6
  max-weather-gets: 2
```

run-id为本次唯一的1～64位安全ASCII标识；路径必须为批准的临时目录父级，实际子目录由harness安全创建且仅使用新库。所有字段严格校验、未知键拒绝；预算不得高于示例上限，重试必须0；城市坐标合法、两个消息非空且通过安全检查。生成的运行配置补齐本功能规定的上下文/文件/工具容量、超时与输出Token上限，不通过配置减少必要安全校验。

本轮真实天气验收采用无需密钥的 Open-Meteo 响应结构，目标仍须配置明确批准；固定 host/path/query-keys，current字段含 temperature_2m、weather_code、wind_speed_10m。查询失败、数据无效或超限均FAIL，不使用缓存伪造天气或自动换源。以下3.1规则是本轮固定验收判据，不新增可放宽门槛的配置键。

### 3.1 天气事实的确定性判定

1. **城市与数据结构**：请求中的latitude/longitude必须分别等于配置坐标的十进制值；响应latitude/longitude须为有限数值且在合法经纬度范围内，与配置值每轴绝对差均≤0.1°（包含边界），用于容纳天气网格坐标偏移，不凭城市名文字判定。current.temperature_2m、wind_speed_10m为有限数值，风速非负，weather_code为整数；缺字段、类型错误或超出坐标容差均失败。
2. **单位与时间**：温度单位必须取自本次响应的current_units.temperature_2m，且必须为`°C`；缺失、华氏或其他单位均失败，不猜测或自动换算。响应timezone必须等于配置zone，current.time按该ZoneId解析为Instant；与该响应接收时刻的绝对差≤2小时（包含边界）才有效，缺失或无法解析均失败。离线测试使用固定Clock。
3. **事实归属**：每次最终答复均绑定到生成该答复的Provider请求中最新一条有效天气观察，通过内存观察器核对其session/process/call来源和实际发送内容；第2次处理未再次GET时使用会话恢复的前次有效观察，若已再次GET并送入模型则以新观察为准。未进入该请求的天气值、另一会话数据或同一请求中的旧观察不能充当事实依据；没有有效观察即失败。
4. **数值与建议**：两次固定用户消息均要求唯一的`气温：<有符号十进制数> <摄氏单位>；建议：<非空文本>`字段，中文或ASCII冒号/分号及字段周围空白均可，摄氏单位仅接受`°C`、`℃`、`摄氏度`并归一到`°C`。基准气温由绑定观察的原始JSON十进制数按BigDecimal、HALF_UP舍入到一位小数；提取完整数值后按十进制数值相等比较，允许末尾零差异和等值正负零，不用子串或二进制浮点近似匹配，不接受华氏换算。气温/建议字段缺失、重复或数值不符均失败；建议去除空白后至少含一个汉字或字母。不得仅凭非空答复、命中“天气”或出现某个温度数字通过。
5. **离线反例**：纯内存断言必须覆盖坐标恰为/刚超过0.1°、错误城市、缺失/错误单位、正负温度舍入边界（如12.35→12.4、-12.35→-12.4）、1.2误匹配11.2、完整数值不符、陈旧/缺失时间、空/重复建议字段、未转发观察、跨会话事实，以及第2次处理已取得新观察却仍引用旧值。测试只用合成天气与消息；导出只保留weatherValid/observationForwarded/factMatched/advicePresent等布尔结果，不含天气或答复正文。

### 3.2 执行约束


1. 操作者显式启用一次本轮验收，配置与凭据齐全；默认缺条件标NOT_RUN且非零退出，不以JUnit skipped当PASS。
2. 新建一个会话，依次两次用户处理：查询固定城市天气；同会话沿用该结果给出建议。每处理 max_iterations=3，Provider max_retries=0，全运行最多6次模型逻辑/物理发送、2次真实GET；守卫必须在外呼前扣额度。无预探测、隐式重跑、失败后换模型/换源。
3. 至少一次模型主动提出http_get；工具取得有效实时天气；观察在下一Provider请求中出现。通过内存测试观察器校验结构化事实传递，只记录布尔断言，不输出Prompt/天气正文。
4. 两次答案均按3.1的坐标、单位、时间、观察归属和温度舍入规则通过判定，且建议字段非空；同会话追问使用已保存上下文。人工架构/语义复核可补充说明，不能替代链路与事实断言。
5. 两次处理均COMPLETED，关闭并重开临时存储，用session/process/request/call映射核对模型与工具审计、状态及计数。保存的消息仍受敏感保护；导出证据不包含正文/参数/秘密。

## 4. 证据与 verifier

已实现 `scripts/verify-react-smoke.py --config <专用YAML> --started-at <执行前UTC时间> --maven-exit-code <真实退出码>`，从配置取得本轮runId并重新计算非秘密指纹，联合读取独立Failsafe XML与JSON及只读重开审计，不仅信任JSON中的PASS或自报开始时间。

JSON白名单：runId/start/end/configFingerprint、sessionId、两个processId、状态、acceptedIterations、logicalRequests、physicalSends、toolExecutions/weatherGets、weatherValid/observationForwarded/factMatched/advicePresent、auditReopened/associationValid、固定错误类别。不得保存凭据值/哈希、个人路径、完整URL、提示词、响应正文。配置指纹只对非秘密声明计算，不读取凭据值；本地证据保存于被忽略目录，提交的验证摘要只留计数/结论/指纹。

PASS必须同时满足：本轮runId/时间与指纹匹配，恰发现1个用例、零skip/failure/error、Maven=0、两个处理完成、最低真实工具链存在、事实断言全部true、总量不超预算、重开审计逐条对应。XML缺失/零发现/缺配置/缺凭据/未启用为NOT_RUN；已执行的类加载、调用、断言、关联、超预算、陈旧或证据矛盾为FAIL。两者均非零，均不能算通过。

验证器自身默认离线测试必须覆盖成功、缺XML、零发现、skip、failure/error、陈旧/错误runId、预算超额、伪造PASS、审计不一致，以及任一天气事实布尔断言为false却自报PASS。WeatherFactAssertionsTest独立覆盖3.1的原始合成数据判定，Python验证器不读取正文、不替代该Java测试。默认测试发现数为零不得声称功能通过；聚焦reactor命令允许上游无匹配时，另核对目标模块XML真实发现数和零跳过。

首次已授权验收FAIL保留。批准A路线完成修订和工程验证后，用户重新授权一次，run-id react-20261022-184544-25261963 的Maven/独立测试/verifier及审计重开全部PASS，实际3模型发送/1GET。后续新的真实运行仍需明确启用、新runId及受控配置/凭据/预算，不隐藏重跑。validation.md保留两次实际结果，不覆盖002的51任务或005记录。

## 5. 实施后的完成矩阵

本节为T134的实际结论，不改变前述判据。FR-001～039逐行映射到第2节A组，SC-001～012同样映射；由第1节定位实际测试，由tasks.md“验收追溯与独立检查”定位任务。全部A01～A16现为PASS（各自范围如下），因此没有以覆盖计划代替执行的待验收条目。

| 验收组 | 实际范围与结果 | 证据 |
| --- | --- | --- |
| A01～A12 | PASS；会话/循环/工具/上下文/安全/CLI的确定性及生产装配测试，Linux强制SDS正向，macOS明确拒绝 | validation.md各故事记录及A路线工程门禁，87套件297项零失败错误跳过 |
| A13 | PASS；完整Maven/静态质量/秘密/依赖门禁、Python验证器9项、新JAR初始化探针 | validation.md“新版工程门禁”；依赖103项0报告漏洞，OSS Index覆盖限制保留 |
| A14 | PASS；新授权实跑恰1独立IT零失败错误跳过、3模型发送/1GET、两处理COMPLETED、事实断言及重开审计全true | validation.md“新版真实天气验收：PASS”；保留首次FAIL原记录 |
| A15 | PASS；自控ReAct、Memory/Sandbox统一边界、模块/事务/敏感及平台限制 | architecture-review.md及各项对应回归 |
| A16 | PASS；配置/快照/首轮及工具续轮真实loopback wire/拒绝与指纹，真实天气亦用同一生产链路 | ProviderThinkingModeTest、CliRuntimeWiringTest、ReactSmokeEvidenceTest；T136～T141及新A14 |

完成指本次003功能的批准范围，不包含thinking工具续轮、完整长期Memory/Tool体系、Web业务入口或生产部署；未自动推进后续故事。

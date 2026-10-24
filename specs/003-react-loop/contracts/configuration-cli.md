# YAML 配置、启动与 CLI 契约

**状态**：以下 ReAct/CLI 已实现并通过离线验证，证据见 validation.md。正文读取仍要求 SecureDirectoryStream；macOS 拒绝、Linux 完成正向验收。既有 Provider/profile.yml 格式、启动快照与凭据引用继续保留。

## 1. 配置来源

### 批准的thinking配置增量（2026-10-22）

全局 `aegisos.providers[]` 新增可选 `thinking-mode: disabled`，仅接受此精确字符串。未填写时不添加thinking请求扩展；不按Provider名称/模型/端点推断。显式声明时生产mapper在首轮、工具回填后的续轮以及该Provider所有逻辑请求中发送 `thinking: {type: disabled}`。模式在启动/重启加载并固定，不逐请求读取配置。CLI严格YAML入口在加载阶段拒绝显式null、空值、enabled、未知值及对象/列表结构，不回显原字段。Spring属性绑定入口中，缺省/null绑定为未声明；空字符串、enabled及未知标量注册为不可用，且不解析对应凭据或外呼。不得把Spring绑定的null与CLI严格YAML中显式null的拒绝规则混为一谈。

课程验收专用YAML的provider必须包含 `thinking-mode: disabled`，Java/Python解析器一致校验，并将该声明纳入非秘密指纹。生成的临时运行配置必须传给同一生产配置/mapper，禁止仅在harness补写HTTP字段。默认init模板只以注释说明该选项，不暗中改变所有Provider。该选项不是跨供应商通用thinking支持承诺；本轮不支持thinking工具续轮、不存储reasoning_content、不修改V3 schema。

- `init --workspace <绝对目录>` 创建 `.aegisos/config.yml`、`.aegisos/agents/weather/profile.yml`、该目录 AGENT.md、公共 skills 目录及可选 Bootstrap 示例。不创建数据库，不解析凭据，不启动 Spring/网络。
- `chat --workspace <绝对目录> --agent <Profile名称> [--session <身份>]` 仅从该工作区 `.aegisos/config.yml` 加载全局 YAML，并通过 boot 显式传入规范化 workspace-root。不按启动 cwd 误读另一份个人配置；相对 `aegisos.audit.path` 解析到该工作区。
- 全局 Provider/沙箱/ReAct 预算与 Profile/绑定关系只启动加载；指令正文每次构建重读。文件正文不参与 Spring 占位符解析。
- config.yml 缺失/非法、预算缺失、目标许可非法明确报错。默认无 Provider 时保留既有无子命令 Web 基础启动兼容；只有实际启用 Agent/CLI 运行时才强制 ReAct 预算，不让旧健康检查因未设置预算失效。

## 2. 全局示例

下面是可审查示例，init 生成时默认 `targets: []`；天气目标必须由操作者明确批准后填入，不自动联网探测。

```yaml
aegisos:
  audit:
    path: .aegisos/aegisos.db
    busy-timeout-ms: 1000
  providers:
    - name: deepseek-main
      adapter: openai-compatible
      models: [deepseek-flash]
      base-url: https://api.deepseek.com
      credential-ref: env:DEEPSEEK_API_KEY
      thinking-mode: disabled
      max-retries: 0
      connect-timeout: 5s
      read-timeout: 60s
  react:
    max-context-bytes: 262144
    max-message-bytes: 32768
    max-context-file-bytes: 32768
    max-context-files: 32
    max-tool-arguments-bytes: 8192
    max-tool-result-bytes: 16384
    max-tools-per-response: 8
    max-output-tokens: 1024
    zone: Asia/Shanghai
  sandbox:
    http-get:
      dns-timeout: 2s
      connect-timeout: 3s
      read-timeout: 5s
      execution-timeout: 10s
      max-response-bytes: 8192
      targets: []
```

所有 max 值均为整数、正值、启动校验；max-context-bytes 必填（4096～1MiB），其他字段缺省使用以上示例值。max-message/file/result 必须≤总预算；原始HTTP响应≤工具结果容量，结构化观察编码后还须≤单消息。max-context-files≤64、max-tools-per-response≤32、max-output-tokens≤8192；参数≤32768、文件/消息/结果≤1MiB且服从总量。无效值拒绝，不偷偷夹取边界值。

Sandbox超时均正且≤30s，dns/connect/read≤execution；全局最多8个并发工具执行许可，DNS最多4个在途任务（固定硬限制）。Provider的connect/read超时继续遵循US1独立配置（示例read=60s），不受Sandbox的30s上限约束。Provider传输响应硬限制1MiB，独立于ReAct逻辑上下文预算。

明确批准的目标示例（真实天气验收可使用无密钥 Open-Meteo；具体允许策略需验收配置批准）：

```yaml
targets:
  - scheme: https
    host: api.open-meteo.com
    port: 443
    method: GET
    paths: [/v1/forecast]
    query-keys: [latitude, longitude, current, timezone]
```

目标必须使用规范主机、精确路径，不允许 `*`。测试 loopback 策略由测试装配注入，不在 init 生产配置中暴露“允许所有私网”开关。凭据仅 `env:NAME` 引用，不提供密钥明文、对话获取凭据或 --api-key 参数。

## 3. Agent 示例

```yaml
name: weather
description: 手动查询天气并给出建议
identity:
  agent_name: 天气助手
  prompt: 基于工具返回的实际天气事实给出简短中文建议。
provider:
  name: deepseek-main
  model: deepseek-flash
  temperature: 0.1
tools: [http_get]
bootstrap: []
settings:
  max_iterations: 10
  max_history_turns: 20
```

保持既有 Profile 下划线键；全局 Spring 配置用连字符。max_iterations 缺省10、范围1～100，max_history_turns缺省20、范围1～1000，由 ProfileLoader 在注册 Profile/AgentSource 之前调用 US2 运行策略严格校验；超范围项只产生固定 INVALID_FIELD 诊断，不能创建会话或保存 USER 消息，其他合法配置独立注册。循环仍保留防御性检查。bootstrap 空/省略采用可选默认约定；非空是显式路径列表。skills 继续从相对目录绑定产生，notify_channels仍为名称引用，不新增 YAML skills、通知执行或调度。

## 4. 命令与输出

```text
java -jar <boot.jar> init --workspace <绝对目录>
java -jar <boot.jar> chat --workspace <绝对目录> --agent <name>
java -jar <boot.jar> chat --workspace <绝对目录> --agent <name> --session <id>
```

init 先预检全部待创建文件，使用 CREATE_NEW，已有目标文件即安全拒绝，不覆盖或“更新模板”；若创建中途失败仅清理本次确定新建的临时文件，不删除已有内容，报告可重试的固定错误。禁止 symlink 工作区边界绕过。

chat 从 stdin 到 EOF 读取一个请求；本轮不实现 REPL。读取前设置字节上限，严格 UTF-8，空/超限/敏感输入拒绝。无 session 时创建会话，指定 session 时严格恢复；同一 Profile 绑定不会自动改变。stdout 使用结构化 JSON：sessionId、processId、status、iterationCount、reply、errorCode、persistenceConfirmed；正文经过安全检查，日志只到 stderr。shell history/进程参数中不提供正文或密钥。

| 退出码 | 意义 |
| --- | --- |
| 0 | init 成功或处理 COMPLETED 且已持久确认 |
| 2 | 用法、输入、配置、未知/错归属会话等外呼前拒绝 |
| 3 | SESSION_BUSY 或 WORKSPACE_IN_USE |
| 4 | MAX_ITERATIONS，非完成 |
| 5 | 处理失败、审计/保存失败、确认未知或中断 |

init/chat 采用 JDK 固定解析器，拒绝重复/未知选项。boot main 先分流：init 不启 Spring；chat 用专用非Web source、WebApplicationType.NONE，显式导入 Provider/Profile、Storage、Memory、Tool、Agent、CLI 配置，退出时关闭 context、运行锁与网络资源。无子命令仍使用既有 Web 基础入口；不新增 Provider 测试 HTTP 接口。

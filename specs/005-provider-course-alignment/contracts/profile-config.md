# 配置契约：Agent Profile 与全局连接

**状态**：拟实现 | **故事**：A | **要求**：FR-001～FR-007、FR-019、FR-022

## 唯一配置来源与快照

默认工作区根为进程启动时明确解析的工作目录；允许通过受信任的 `aegisos.workspace-root` 指定根，解析一次后冻结。仅扫描 `<root>/.aegisos/agents/` 的直接子目录中的精确文件名 `profile.yml`；不递归寻找其他 YAML，不从 AGENT.md frontmatter 或 `.aegisos/profiles/` 回退。

Agent 目录中缺 profile.yml 时报告安全的 PROFILE_FILE_MISSING，不注册；AGENT.md 是否存在不影响本节元数据加载，本轮不读取其正文。全局 Provider 按现有 application*.yml 构造快照后再装配 Profile。配置变更、新增/删除和环境变化都只在重启后生效；无 watcher、每请求读盘或动态注册接口。

## 两层 YAML 示例

以下为实施后配置示例，无真实凭据，不是已成功连通证明。模型名来自研究日期的公开资料，执行时仍须确认允许列表和厂商可用性。

```yaml
# application-provider-smoke.yml：全局只定义连接和允许模型。
aegisos:
  providers:
    - name: deepseek-main
      adapter: openai-compatible
      models: [deepseek-flash]
      base-url: https://api.deepseek.com
      credential-ref: env:DEEPSEEK_API_KEY
      max-retries: 0
      connect-timeout: 5s
      read-timeout: 60s
```

```yaml
# .aegisos/agents/course-smoke/profile.yml：名称精确选择，省略温度。
name: course-smoke
description: Provider 课程冒烟元数据
identity:
  agent_name: 课程验收助手
  prompt: 仅用于固定的非敏感测试
provider:
  name: deepseek-main
  model: deepseek-flash
tools: []
mcp_servers: []
channels:
  - name: cli
    config: {}
notify_channels: [team-lark]
schedules:
  - key: daily-check
    cron: '0 0 8 * * *'
    zone: Asia/Shanghai
    message: 执行日常检查
bootstrap: [SOUL.md]
settings:
  max_iterations: 10
  max_history_turns: 20
```

此例的身份、Channel、通知、调度和 bootstrap 只是字段承载示例，加载时不运行。冒烟专用夹具只需 name/provider 等必要字段；不会将以上 prompt 自动发送。

## 来源和覆盖顺序

| 内容 | 唯一来源与规则 |
| --- | --- |
| Provider 连接/允许模型/重试/超时 | 全局 YAML 经现有 Boot 配置绑定；保留显式名称、重复全局名称拒绝启动的规则 |
| 凭据 | credential-ref: env:NAME；环境值仅进入受控连接对象，Profile 中既不保存值也不保存连接配置 |
| Provider 选择/模型/温度 | profile.yml；全局允许集合只约束、不补默认模型；请求不能覆盖 Profile 选择 |
| skills | `.aegisos/agents/<dir>/skills/<binding>` 相对软连接；YAML 出现 skills 键即无效 |
| notify_channels | YAML 安全名称列表；不支持 URL、Webhook、认证配置；存在性及通知解析延后 |
| 其他字段 | 按[数据模型](../data-model.md)逐项解析，未知顶层/固定结构键拒绝，不静默丢失 |

不使用扫描顺序、Bean 类型、models 第一项或 SDK 默认模型进行补选。Profile 的 provider/model 必填；已注册但缺凭据的 Provider 可被 Profile 引用，真正调用在审计拒绝后返回不可用。

## 环境引用

profile.yml 仅 provider.name、provider.model、provider.temperature 支持全值 `${ENV_NAME}`；变量名为 `[A-Za-z_][A-Za-z0-9_]*`。不支持嵌入字符串、默认值语法、递归引用或正文展开；这三个字段出现其他占位形式即错误。读取一次后按目标类型校验：temperature 必须是有限 [0,2] 数值；name/model 必须满足已有标识及全局限制。

变量不存在、空字符串或解析非法使该 Profile 无效；错误不输出变量值。其他字段中的 `${...}` 按字面文本保存，不读取环境；配置维护者不得把真实凭据或秘密变量引用藏入这些元数据。

## 安全解析与路径

- 单文件读取上限 262144 字节/codepoints，限制在读入完整字符串之前生效；最大嵌套 32、节点 4096、列表 256。超限条目隔离，不执行配置内容。
- SnakeYAML 2.6 SafeConstructor；拒绝重复/递归 key、anchor、alias、merge、自定义/危险标签、非字符串映射键、多文档及非映射根。允许标量仅字符串/数值/布尔/null，拒绝时间、集合、二进制及对象实例化。
- 不根据扩展名启用对象构造；事件层和业务字段层均限制；YAML 解析器原始异常及输入片段不得传播到日志、异常、报告。
- 根目录先 realpath 校验；Agent 目录/profile.yml/skills 容器不能通过软连接越界。每个技能绑定必须是相对软连接，最终目标必须存在、为目录并位于真实公共 skills 根内；绝对、悬空、循环、越界及普通文件均使该 Profile 无效。
- 不加载 SKILL.md、bootstrap 或脚本正文，不解析通知端点。工作区应为可信管理员目录；此检查不提供防敌对并发文件替换的 OS 沙箱保证。

## 错误与冲突

安全诊断包括固定 code、安全相对来源、可选行/列；不包括原始 name、原始键、值、绝对用户路径、Throwable。分类至少包括 READ_FAILED、INVALID_YAML、LIMIT_EXCEEDED、INVALID_FIELD、ENV_MISSING、INVALID_SKILL_BINDING、UNKNOWN_PROVIDER、MODEL_UNAVAILABLE、DUPLICATE_PROFILE。

两阶段构建索引，同名候选全部不注册；坏文件不阻断其他合法且无冲突项。全局 Provider 重名仍阻断全局装配，不与 Profile 的条目隔离混为一谈。缺失/空 Agent 根为空索引且有安全提示；不尝试扫描上级目录。

## 配置兼容验证

保持当前 application.yml、application-dev.yml、application-metrics.yml、application-provider-test.yml 的实际配置含义、环境差异和日志关闭策略。生产及相关测试资源中不允许同名旧 application*.properties 覆盖 YAML。迁移已由并行基线完成，本功能只验证/补齐遗漏，不重做历史工作。JVM/Maven 参数及 Spec Kit JSON 不受应用 YAML 文件格式约束。

# Provider 配置契约

**状态**：计划配置，尚未实现绑定。US-1 仅启动/重启加载。

## 配置形态

使用列表保留重复名称以供显式校验，不能用 YAML map 覆盖同名项。以下只含非敏感配置和环境变量名称，不含凭证值。

```yaml
aegisos:
  providers:
    - name: deepseek-main
      adapter: openai-compatible
      models: [deepseek-chat]
      base-url: https://api.deepseek.com
      credential-ref: env:DEEPSEEK_API_KEY
      max-retries: 0
      connect-timeout: 5s
      read-timeout: 60s
    - name: kimi-main
      adapter: openai-compatible
      models: [moonshot-v1-8k]
      base-url: https://api.moonshot.cn
      credential-ref: env:MOONSHOT_API_KEY
      max-retries: 2
      connect-timeout: 5s
      read-timeout: 60s
```

模型示例只说明配置结构，真实厂商是否仍支持该模型必须现场核实。`base-url` 是不含 `/v1/chat/completions` 的 API 根地址；首批兼容适配器追加该路径一次。自定义网关若路径不同，应明确扩展路径配置并测试，不能猜测/重复追加。

| 字段 | 规则 |
|---|---|
| name | 必填、格式见数据模型、区分大小写、全局唯一；重复配置阻止启动 |
| adapter | 首批支持 openai-compatible；其他值明确不可用，不猜测协议 |
| models | 非空显式允许集合；不得把集合首项作为默认请求模型 |
| base-url | 可省略，openai-compatible 默认 https://api.openai.com；覆盖值只允许无 userInfo/query/fragment 的 HTTPS URI；测试 profile 允许本机 loopback HTTP stub；不接受含凭证 URL |
| credential-ref | 首批仅 `env:变量名`；不接受明文 key；运行时解析一次，缺失/空值令该 Provider 不可用 |
| max-retries | 非负整数，默认 0；N 表示最多 N+1 次外呼。计算避免整数溢出，配置非法不得悄悄归零 |
| connect-timeout | 正数时长，默认 5s |
| read-timeout | 正数时长，默认 60s |

## 生效与错误边界

- 启动建立不可变注册表；重复名称或无法绑定的配置拒绝启动。单个条目缺密钥、未知 adapter、空模型等可判定配置问题保留不可用状态，明确请求错误，不能借其他 Provider 代替。
- 启动不进行付费模型探测；数据库初始化/版本校验必须完成，无法就绪时不得发送模型请求。
- 配置文件或环境凭证运行中变化不影响已构造客户端；重启后重新绑定。US-1 不监听配置目录，也不读取 Agent Profile。
- `max-retries` 由业务循环使用；SDK `maxAttempts=1`、自动 Tool 执行=false 是强制安全选项，不能由请求或该 YAML 覆盖。
- 受控密钥存储为未来适配点；本次不引入密钥管理服务、动态轮换或远程配置中心。
- 禁用请求/响应正文和 HTTP 认证头观测；不打印配置对象、完整 URL、原始 SDK 异常。Provider/模型标识也禁止用来承载秘密。
- 使用本地 stub 的测试凭证在内存临时生成，测试日志不输出；配置示例和提交中不得包含真实值。

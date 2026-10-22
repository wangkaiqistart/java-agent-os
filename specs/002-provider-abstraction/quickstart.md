# US-1 验证指南

**状态**：US-1 代码及测试已实现；依赖安全门禁于 2026-10-21 23:23 通过，T051 已完成，US-2 的 US-1 前置门禁已满足。本地执行结果见 [validation.md](validation.md)。以下命令可复现自动化验证，不要求真实模型凭证。

## 前置条件

在仓库根目录操作，使用 Java 21/Maven 3.9.x。自动测试启动本地 HTTP stub 和临时 SQLite，使用内存临时凭证，不要求真实厂商 key。无 Maven Wrapper，使用本机 `mvn`。

```bash
java -version
mvn -version
```

数据、错误、配置分别参照 [数据模型](data-model.md)、[Java 契约](contracts/provider-api.md)、[配置契约](contracts/provider-config.md)。US-1 无对外业务命令/HTTP 端点，不提供无法执行的 curl Demo。

## 配置与 Java 调用

生产配置键为 `aegisos.providers` 列表，字段见 [配置契约](contracts/provider-config.md)。默认列表为空，不创建默认 Provider，不探测模型。配置示例已注释在 `aegisos-boot/src/main/resources/application.properties`；可通过外部 properties 配置文件提供同名配置键。凭证只使用 `env:变量名`，启动时从操作系统环境读取一次；修改配置或环境后需重启。

`base-url` 使用不含 `/v1` 或 `/chat/completions` 的 API 根地址，客户端仅追加一次 `/v1/chat/completions`。HTTPS 不允许 userInfo、查询参数或 fragment；仅显式 `test` profile 允许 loopback HTTP。未知协议、缺凭证、空/非法模型集合、非法数值或端点使该 Provider 不可用；无效/重复名称使启动失败，不回退。

其他模块通过 `com.aegisos.core.provider.ProviderService` 调用：

```java
ModelResponse response = providerService.chat(
    ModelRequest.text("deepseek-main", "deepseek-chat", "你好"));
```

这段调用会访问配置的模型，不属于默认构建或启动行为。US-1 只提供 Java 接口，不含业务 REST、对话 CLI 或 Agent Profile 加载。`ProviderException.failure()` 返回 requestId、稳定错误码、尝试次数和请求级 externalOutcome；不得在外层补做隐藏重试。Tool schema 只描述能力，返回的 ToolCall 交由后续 ReAct/Sandbox 功能处理。

审计文件默认 `.aegisos/aegisos.db`，可设置 `aegisos.audit.path`；有限锁等待 `aegisos.audit.busy-timeout-ms` 默认 1000。首次启动执行 V001 SQL，后续校验版本；不用自动 DDL 或内存回退。每次尝试先提交 STARTED，返回后更新终态。begin 失败不外呼，complete 失败保留未确认记录并停止重试。重启不会重放 STARTED。

## 自动化验证命令

以下测试类已接入 Surefire；不能因类不存在而跳过验证。所有自动测试使用 `*Test` 命名，由 Surefire 执行；不要创建未接入 Failsafe 的 `*IT` 后声称已运行。

```bash
# 聚焦 provider/storage 的全部单元与本地集成测试，-am 构建依赖
mvn -pl aegisos-provider,aegisos-storage -am test

# boot 中命名的最小 Provider 装配测试；其他 reactor 模块没有该类可以继续
mvn -pl aegisos-boot -am -Dtest=ProviderWiringTest -Dsurefire.failIfNoSpecifiedTests=false test

# 完整测试及完整干净打包，两项都必须通过
mvn test
mvn clean package
```

检查 `aegisos-provider/target/surefire-reports/`、`aegisos-storage/target/surefire-reports/`、`aegisos-boot/target/surefire-reports/`，确认目标测试实际存在、数量大于零且无失败/错误。第二条命令的容错参数仅用于不含指定类的依赖模块，不可用来掩盖 boot 测试缺失。不得添加 skipTests 或 maven.test.skip 绕过门禁。

## 验收矩阵

| 场景 | 操作与预期 | 规格 |
|---|---|---|
| 故事 1：成功响应 | 本地 stub 返回文本/用量；身份与请求一致，一行 SUCCEEDED；无用量则字段为空 | SC-003/005 |
| 故事 1：重试 | 默认 0 时一次外呼；配置 2，前两次临时断连后成功时共三次、三条审计；耗尽也不得第四次 | SC-006 |
| 不可重试 | 400/401/403/429/5xx/协议错误不自动重试；缺凭证零外呼 | SC-002/006 |
| 故事 2：显式路由 | A/B 两个 stub，各指定请求只进入选定端点；未知/缺 Provider、未知/缺模型均零外呼 | SC-001/002/008 |
| 重名与配置错误 | 重名启动失败；其他不可用 Provider 返回明确错误，不 fallback | SC-002 |
| 启动快照 | 启动后改配置/凭证引用仍调用原 stub；关闭并重建上下文后切换到新 stub | SC-007 |
| Tool 边界 | stub 返回结构化 Tool 调用，响应保留 id/name/参数；工具回调计数零，无自动第二次模型请求 | FR-012 |
| 故事 3：拒绝审计 | 可写存储下校验拒绝产生单行 REJECTED/attempt_no=0，零外呼 | SC-003 |
| 前置提交 | stub 请求处理器以第二连接读取 STARTED；模型阻塞时其他写事务可提交 | SC-009/FR-016 |
| 前置失败 | 连接/锁/磁盘错误或注入 begin 失败：该次外呼为零、AUDIT_UNAVAILABLE；在后续重试前失败也停止 | SC-009 |
| 终态失败 | 外呼后 complete 提交失败，保留 STARTED/结果未确认；返回审计故障，不重试、不返回普通成功 | 规格一致性说明 |
| 重启持久化 | 关闭/重开临时文件数据库，终态仍在、STARTED 不重放 | FR-008/016 |
| 敏感保护 | 临时凭证哨兵和消息/响应中的敏感哨兵不出现在审计、捕获日志、异常、构建产物中 | SC-004 |
| 最小装配 | ProviderWiringTest 无 ReAct/Memory/Tool/Web 业务 Bean，仍完成本地调用与真实 SQLite 审计 | SC-005 |

HTTP stub 计数必须是真实收到的请求数，不能仅断言 mock adapter 调用次数。只有临时网络/超时触发重试；读取超时可能已到达模型，不能把失败写成确定未发送。

## 可选真实厂商验证

实施后通过开发测试入口调用 `ProviderService`，明确提供 Provider/模型；凭证在受控 shell/IDE 环境中注入，不把值写进命令历史、配置示例或本文件。真实验证若未执行，报告标记 `NOT_RUN`，不能以本地 stub 通过替代。厂商模型可用性、实际 Token 统计及费用单独记录。

## 完成判据

三个规格故事全部通过；完整 Maven test/package 成功；提供真实测试数、stub 请求计数、SQLite 重开检查和敏感断言结果。任一门禁失败均不能声明 US-1 完成或让 US-2 开始依赖。真实厂商调用、CI 和部署不包含在本地测试结论内。

# 验证运行指南：Provider 课程补齐

本文件对应本轮已实现的本地验证、手工迁移脚本和显式真实冒烟入口。各项当前结果以 [实施验证](validation.md) 为准；DeepSeek单次真实验收已于2026-10-22通过，具体配置、请求/审计计数和门禁见实施验证；其他调用仍须满足授权与条件。

## 运行前提

- 仓库根目录，Java 21、Maven；从已批准 Boot 4.1.1 / Spring AI 2.0.1 继续，不覆盖安全依赖版本。
- 默认验证仅需本地 stub/临时文件/SQLite JDBC，不依赖真实模型凭据。
- 手工迁移另需 sqlite3 CLI 和离线一致性备份；真实冒烟报告核验需 Python 3 标准库。
- 每个故事有测试和 Maven 构建，失败阻断依赖故事；本轮功能证据写新 validation，不覆盖原 US-1 的任务或验证记录。

## 已有构建与门禁入口

以下是本地及 CI 的实际工程入口；本轮执行证据见 validation.md。quality 也会构建/测试，依赖扫描可能访问漏洞库，均不能替代真实模型成功。

```bash
cd /Volumes/Data/my_project/java-agent-os
mvn -version
mvn test
mvn clean package
bash scripts/check.sh quality
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

预期：九模块 reactor 成功，默认零真实模型外呼；秘密扫描无泄漏；依赖门禁实际通过。缺工具/漏洞库更新失败不能记 PASS。凭据仅用现有受控环境/Keychain 流程注入；不得把模型或 NVD 密钥写到命令参数、文件、日志或 Maven 属性。

## 按故事运行本地聚焦测试

以下类名已由本轮实现并实际发现；核对最新 validation.md 中的结果，不能以“无匹配测试”通过。每次聚焦命令后都执行 `mvn test` 和 `mvn clean package`，保存该故事的实际证据。

| 故事 | 聚焦命令 | 结果要求 |
| --- | --- | --- |
| B 存储/迁移 | `mvn -pl aegisos-storage -am -Dtest=JpaLlmCallAuditStoreTest,AuditSchemaMigrationTest,AuditStorageFailureTest -Dsurefire.failIfNoSpecifiedTests=false test` | V2 新库/旧库/重开/回滚/约束通过；目标 storage 报告中各类实际执行 |
| B 服务基础 | `mvn -pl aegisos-provider -am -Dtest=ProviderServiceTest,ProviderRetryTest,ProviderAuditFailureTest -Dsurefire.failIfNoSpecifiedTests=false test` | 前置失败零发送；重试有限；终态失败 UNKNOWN 并停止 |
| A 配置路由与 B 会话集成 | `mvn -pl aegisos-boot -am -Dtest=ProfileLoaderTest,ProfilePathSafetyTest,ProfileEnvironmentTest,ProfileSnapshotTest,ProfileRoutingTest,ApplicationYamlCompatibilityTest,ProviderSessionTest -Dsurefire.failIfNoSpecifiedTests=false test` | 全字段/坏文件/环境/快照/YAML/路由/两会话审计，全部走本地 stub |
| C schema | `mvn -pl aegisos-provider -am -Dtest=ToolSchemaContractTest,OpenAiCompatibleAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test` | 最终 JSON 完整、非法零发送、零执行、零第二轮 |
| D 默认/负例 | `mvn -pl aegisos-boot -am -Dtest=DefaultProviderIsolationTest,ProviderSmokeEvidenceTest,ProviderSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test` | 有凭据仍零真实外呼；缺条件/零发现/跳过/旧报告非 PASS |
| E 交付 | 上节全量 test/package 和三项门禁，人工核对注释/追溯表 | 原测试保留，7 条有实际证据，未完成如实标记 |

聚焦命令中的 Surefire 参数只允许依赖模块没有所选类，**不能证明目标模块发现了测试**；必须核对本轮目标 surefire-reports 中每个列出的类确实执行且零失败。完整 reactor 测试和逐场景覆盖仍必需。真实冒烟不使用此放宽参数。

## 旧库手工升级

先停止应用和其他写入连接，准备合成 V1 库完成迁移测试，再由管理员对需要升级的真实库操作。流程、保护条件和备份/回滚见 [审计契约](contracts/audit-storage.md)；本轮仅验证临时合成数据库，未迁移个人现有 `.aegisos` 数据。

管理员审核并安排停机窗口后的命令示例为：

```bash
# .backup 通过 SQLite 做一致性备份；先确认目标备份不存在且位置受控。
sqlite3 .aegisos/aegisos.db '.backup .aegisos/aegisos-pre-v2.db'
sqlite3 -bail .aegisos/aegisos.db < aegisos-storage/src/main/resources/db/migration/sqlite/V002__provider_course_audit.sql
```

预期：版本为 2、原列/行数等值、四状态新字段映射正确、历史 session_id=null、新版重开可写。锁、版本/结构不匹配、脚本/检查失败立即停止；不得带失败结果启动服务。降级需要恢复一致性备份与旧应用，不能只改版本号。备份/DB 含审计数据，不提交或上传为公开证据。

## 显式真实 DeepSeek 冒烟

只有普通测试/构建及前置故事通过，且具备实际调用授权时才执行。配置见 [Profile 契约](contracts/profile-config.md)，发现/断言/分类见 [验收契约](contracts/acceptance-harness.md)。

专用 YAML 已在 aegisos-boot/src/test/resources/application-provider-smoke.yml 提供，包括全局 Provider 定义及 `smoke.profile-name: course-smoke`、`smoke.session-id: pdf16-smoke-001`。生产配置装配从该 YAML 读取，测试把 Profile 夹具放入隔离临时工作区，把审计路径指定到临时 SQLite，避免使用个人现有数据库。通过非敏感环境变量 AEGISOS_SMOKE_CONFIG 指定专用 YAML 文件；示例配置不能含真实 key。

DeepSeek 名称/模型显式为 deepseek-main / deepseek-flash；凭据为 env:DEEPSEEK_API_KEY，仅通过受控环境继承，禁止在终端键入真实值供历史记录保存。Profile 省略 temperature、请求省略 maxOutputTokens；无工具、max-retries=0。服务端默认 thinking 和输出上限可能较大，短提示和超时不等于硬计费上限。既有 `/v1` 路径与deepseek-flash组合已于2026-10-22真实验证通过；后续失败仍记FAIL，不自动换模型/路径。

以下入口已实现，本地负例和一次真实DeepSeek成功均有证据。开始时 clean 清除旧报告，单独运行每条命令，保留 Maven 退出码；即使 Maven 失败，也运行核验器给出安全分类，但不得覆盖失败退出结果。

```bash
export AEGISOS_SMOKE_CONFIG=/absolute/path/application-provider-smoke.yml
mvn -pl aegisos-boot -am -Preal-provider-smoke -Dit.test=ProviderSmokeIT clean verify
python3 scripts/verify-provider-smoke.py --reports aegisos-boot/target/failsafe-reports --evidence aegisos-boot/target/provider-smoke-result.json
```

预期 PASS：目标类发现=1、执行=1、skipped=0、failures/errors=0；物理 HTTP 发送尝试计数=1；响应文本非空；临时库新增恰好 1 条对应 sessionId/requestId 的成功终态审计，重开仍可读。具体字段见验收契约，不输出回复内容或原异常。NOT_RUN/FAIL 都为非成功，课程仍未完成。

缺 key、缺配置、未发现、跳过、旧报告都不能通过。存在 IT 源文件、默认 package 成功、NVD 通过或仅模型返回 HTTP 200 都不足以验收。认证/网络/响应/审计不符不得隐式重发。本轮真实DeepSeek状态为 **PASS**（仅指定配置、一次请求）；证据见validation.md。外层可向核验器传入 `--maven-exit-code <实际退出码>`，非零永不转成功；Failsafe 3.5.2 成功 summary 的 result 实际为字符串 null，仍须核对各项计数。

## 最终检查

对照验收契约的 20 场景及 7 个 PDF 条目，记录每故事的测试和 package、实际安全门禁、真实冒烟统计/审计与批准替代。人工核对关键新增代码的详细中文注释、全部相关 YAML、旧任务与历史未被改写。任一未达标项保留 FAIL/NOT_RUN，不因计划完成或 tasks 勾选而宣称课程验收完成。

实现补充：boot的Failsafe显式使用`${project.build.outputDirectory}`，避免Boot重打包JAR造成应用类不可见；IT先验证应用类路径，类加载故障为FAIL/SETUP，不能误报缺凭据。Failsafe 3.5.2的真实testsuite可不含timestamp，此时核验报告文件mtime与JSON起止窗口；runId、唯一类/方法、全部统计和Maven退出码仍必须满足。预检缺条件仅在XML无运行错误且发送0时为NOT_RUN。

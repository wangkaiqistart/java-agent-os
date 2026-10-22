# 审计契约：V2 字段与离线升级

**状态**：拟实现 | **故事**：B | **要求**：FR-008～FR-012、FR-018、FR-019

## 表、端口与事务

core 定义审计 DTO/端口；provider 不调用 JPA；storage 接受安全参数，二次校验 sessionId/身份/状态。JPA Entity 和 Repository 增加物理 session_id、success、error_message，字段映射见 [数据模型](../data-model.md)。success 使用可空 Boolean，不把 STARTED 解读为失败。

- begin：独立短事务插入 STARTED，成功返回必须代表提交；sessionId、requestId、attemptNo 在该行固定。
- complete：单条条件 UPDATE，`WHERE id=? AND status='STARTED'`，原子写 status、success、error_code、error_message、时间/耗时/用量等；受影响行必须恰好 1。重复完成、无行或约束/提交失败均安全报审计不可用。
- reject：独立事务直接插 REJECTED、attempt_no=0、success=false、耗时 0、安全错误说明；非法身份关联为空。
- 网络发生在数据库事务外；没有跨 HTTP 长事务、自动补偿/重放/修复接口。终态写入失败保留未确认结果并阻止重试。

error_message 从固定错误类别映射生成，端口不接收任意 String。STARTED/SUCCEEDED 为 null，FAILED/REJECTED 为非空安全说明；最大 256 字符。未知历史错误使用固定通用说明，不拼接原 error_code 或厂商正文。旧 error_code 原值在迁移中保留。

SQL CHECK 须显式处理 nullable 值，涵盖状态/attempt/时间/重试/用量/兼容列；故意尝试 STARTED+false、SUCCEEDED+null、FAILED+null/空说明等都必须被拒绝。保留唯一 `(request_id,attempt_no)` 和原 request 索引，新增非唯一 `(session_id,created_at)`。session_id 只格式约束，无会话 FK；不引入会话表。

## 版本资源

| 资源 | 用途 |
| --- | --- |
| `aegisos-storage/src/main/resources/db/schema/sqlite/V001__llm_calls.sql` | 保留原文，旧库/回归夹具依据，不作为新版本建库后自动升级的入口 |
| `aegisos-storage/src/main/resources/db/schema/sqlite/V002__llm_calls.sql` | 拟新增，空库完整 V2 DDL，与迁移终态结构一致 |
| `aegisos-storage/src/main/resources/db/migration/sqlite/V002__provider_course_audit.sql` | 拟新增，手工离线 V1→V2 脚本，不被应用启动器自动执行 |

沿用 aegisos_schema 的 id=1 版本记录，不引入新的迁移框架。启动器先识别当前库，不得在 V1/未知库上先创建对象再拒绝。V1 只读拒绝并提供固定 MIGRATION_REQUIRED 诊断；未知/无版本非空/结构不匹配固定拒绝。仅真正空库在一个事务里创建 V2；合法 V2 验证字段、约束、索引后可用，失败关闭 DataSource。

## 手工迁移步骤与不变量

此流程由实施阶段提供脚本与测试，计划阶段不执行。

1. 停止所有访问该库的应用/任务，确认无其他连接写入。采用 SQLite 一致性 backup API/CLI `.backup` 制作独立备份并验证；不能对运行中的 WAL 库只复制主文件。
2. 以 sqlite3 的 fail-fast 模式执行版本化脚本。开启检查后以 BEGIN IMMEDIATE 取得独占写入窗口；锁/只读/磁盘等失败必须非零退出，不继续复制。
3. 校验精确 V1 版本、表/列/约束、行状态及已知索引。发现未受支持的 llm_calls 自定义索引/trigger 或结构差异，安全停止，不能静默丢弃扩展对象。V2 再执行以明确 ALREADY_MIGRATED 非零退出，不修改数据或重复复制；其他版本拒绝。
4. 建立受完整 V2 约束的新表，复制旧列并派生新列：session_id 全为 null；STARTED→null/null，SUCCEEDED→true/null，FAILED/REJECTED→false/固定安全说明。
5. 校验行数、主键/唯一性、旧字段逐行等值、状态与新增列映射、64 位用量、外键/integrity 检查；完成表替换及已知索引重建；不能删除旧错误记录或填造会话。
6. 只有全部成功才将 version 更新为 2 并提交；任一步失败回滚，版本/表/数据保持 V1。脚本在事务开始前及结束后均有可辨识的安全结果，不输出历史内容。
7. 使用新应用重新打开，确认新字段查询/写入和约束。需要降级时停止新应用，整体恢复一致性备份及旧应用；不得只把 version 改回 1。

迁移资源只使用 SQLite SQL，不包含 CLI 元命令、trigger 定义或应用自定义 SQL 函数；断言通过临时校验表等 SQL 约束实现，必须能由约定 sqlite3 入口执行。默认测试使用现有 Spring JDBC 的 ScriptUtils 执行同一资源，正确识别引号和注释，任一异常回滚并关闭连接，再由 JDBC 重开确认；不能用简单 `split(';')` 代替。手工 CLI 的 fail-fast 入口还应在实施验收时验证；该人工检查不冒充默认测试结果。测试数据使用合成记录，无真实会话或正文。

## 必须覆盖的测试

| 组 | 行为与断言 |
| --- | --- |
| 全新库 | 初始化 V2、成功/失败/拒绝/STARTED 存读、独立连接可见、重开版本及字段正确 |
| 旧库 | 原 V001 生成夹具，四状态、未知 error_code、nullable/大整数用量；手工升级后原列等值、session null、兼容列正确 |
| 幂等/不支持 | V2 重跑无复制；未知版本/无版本非空/损坏/自定义触发器或索引拒绝；旧库启动不自动修改 |
| 回滚/资源 | 复制或校验故障回滚；锁、只读/不可写、提交异常为非成功；原数据/版本保持 |
| 一致性 | 不合法状态组合被 DB 拒绝；complete 条件更新 1 行、重复完成失败；关联不可更换 |
| 调用门槛 | begin 失败无新增发送；complete 失败 UNKNOWN 且无后续重试；此前外呼事实保留 |
| 保护 | 原始厂商异常/凭据/完整消息不落库，错误只包含固定代码与说明；拒绝不保存非法身份原值 |

本契约不提供业务会话查询 API；验收通过受控测试/JDBC 查询审计，不把 schema 升级等同于完整会话持久化。

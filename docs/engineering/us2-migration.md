# US2 V3 手工迁移与回滚

新空库由运行时创建 V3。既有 V1/V2 库只返回 MIGRATION_REQUIRED，不自动升级；V1 必须先按原迁移说明升级至有效 V2。本说明没有执行个人库迁移，也不授权修改生产数据。

## 操作前提

1. 停止全部使用该规范数据库、软连接或硬连接别名的进程，保留旧程序、配置和可恢复副本。SQLite 位于本机文件系统，不能使用共享网络盘。
2. 规范化数据库真实路径；数据库名不得以 `.lock` 结尾（含大小写及软链接规范目标），该后缀保留给运行锁，不能将另一库的 sidecar 用作数据库。确认数据库和 `<真实数据库路径>.lock` 均为单链接普通文件（`nlink=1`）；硬链接或无法验证链接数的文件系统必须停止操作。保持与 RuntimeDatabaseLock 相同的 POSIX record lock，**只锁独立 sidecar `<真实数据库路径>.lock`**，整个备份/迁移/核验/恢复期间不放开。Java 使用 FileChannel.tryLock；Unix 可用 fcntl.lockf。不能对数据库 inode 加锁来代替：同进程关闭 SQLite 物理连接可释放它；也不能再次打开/关闭同一 sidecar 造成锁释放；已有 sidecar 的普通文件/单链接检查必须在打开前完成（打开后仍复核），拒绝硬链接候选时也不能关闭同 inode 的新描述符。`flock` 与此锁体系不同，不能代替；不要删除或替换锁文件试图绕过正在运行的进程。生产拒绝多硬链接库（固定 AUDIT_UNAVAILABLE），不以不同文件名/sidecar 接管相同数据库。
3. 使用 SQLite backup API（或 sqlite3 的 `.backup`）建立一致性备份，另存至批准目录，限制访问权限；验证备份可打开、integrity_check=ok、foreign_key_check 无行，aegisos_schema 恰为有效版本2。不要直接复制仍活跃的 db 主文件，遗漏 WAL 会丢数据。
4. 先在备份的独立副本演练并检查原有表/索引/DDL。原表语义不同、额外 trigger、部分升级或版本未知时停止，不修改迁移 SQL 来绕过检查。

## 增量与核验

在持锁且停止其他写者的副本连接执行：

- `aegisos-storage/src/main/resources/db/migration/sqlite/V003__react_runtime.sql`，不是空库完整 schema。
- 脚本开启外键、BEGIN IMMEDIATE，严格检查有效 V2 DDL/索引/版本、保存旧审计临时副本，新增会话/处理/轮次/关联/交换组/消息/工具审计/事件，旧 llm_calls 全列保持不变。
- 脚本执行前后检查 integrity_check 与 foreign_key_check；脚本内有完整旧行双向 EXCEPT 与行数相等断言。任意 SQL 失败必须在同一连接 ROLLBACK 并关闭，不能跳过错误继续执行 COMMIT。
- 成功后关闭连接，重新打开库，确认 aegisos_schema.version=3（不是 PRAGMA user_version），逐项比较备份与新库的 llm_calls 全列、双向 EXCEPT 均为空；验证必需新表/索引和外键。跨连接不要依赖 TEMP 表还存在。
- 再启动新版本，生产 AuditSchemaInitializer 必须接受完整 V3；真实 Java 测试 ReactSchemaMigrationTest / RuntimeStorageLifecycleTest 负责启动 DDL、外键及运行锁核验。先确认测试通过，再考虑单独的运行验收。

迁移脚本不是幂等升级工具：对 V3 再次执行必须失败且不改变原数据。已有 STARTED 审计保持 STARTED，不伪造外部调用结果；US2 恢复只将遗留处理标为 INTERRUPTED，不重放动作。

## 停机恢复备份

回滚需要重新停止新版本全部写者并持有相同运行锁。先另存当前失败现场，再用 SQLite backup API 从已验证的 V2 一致性备份恢复整个库；保持连接关闭后重开核对版本2、旧审计全列与数量、完整性及不存在 V3 会话表。只有确认完成后才释放锁并用兼容的旧版本启动。

恢复备份会丢弃备份完成之后写入的会话、审计与其他新增数据；不能靠删除新表、降低版本号或拷回活跃主文件实现安全回滚。需保留的新数据另行导出审查，不能自动拼接两库。

## 本仓库的安全演练

```bash
python3 scripts/verify-react-migration.py
mvn -B -pl aegisos-storage -am -Dtest=ReactSchemaMigrationTest,RuntimeStorageLifecycleTest,RuntimeDatabaseLockTest -Dsurefire.failIfNoSpecifiedTests=false test
```

演练脚本没有个人库参数，只生成临时 V2、持锁备份、增量升级、关闭重开、全列双向比较及停机恢复，退出后删除临时文件。本轮实际结果：`PASS:TEMPORARY_V2_BACKUP_V3_BIDIRECTIONAL_COMPARE_REOPEN_AND_RESTORE`，证据 `.tools/us2-migration-rehearsal.log`；Java 迁移/运行锁/恢复测试的最终发现数量见 `specs/003-react-loop/validation.md`。

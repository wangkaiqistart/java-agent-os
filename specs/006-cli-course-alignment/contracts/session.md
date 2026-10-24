# 会话与存储边界契约

关联：[数据模型](../data-model.md)、FR-016～022/029、SC-003～005。

## 公共入口

- core `SessionIdentity(channel,user,profile)`：不可变，值域严格按数据模型；不拼接身份主键。
- `AgentService.getOrCreateSession(identity)`：先检查身份敏感信息与已注册Profile，再通过MemoryService委托原子存储端口，返回既有SessionSnapshot。保留原createSession/profile及process入口。
- `SessionStore.getOrCreateSession(identity)`：只由Memory实现委托；storage完成一次短事务或冲突后安全重读。Memory统一检查写入/读取字段，不能让新方法绕过guard。
- `MemoryService`延续SessionStore门面；不另建竞争的SessionManager，不改变ReAct对历史的访问方式。
- `SessionCatalog.list(query)`：独立只读投影，返回安全分页及明确数据库状态；CLI不接触JPA/JDBC或session_messages。

## 原子性与隔离

1. 命中同一三元组，只返回目标UUID；校验外键、Profile及安全内容，不触发recovery、不修改会话时间戳。
2. 未命中，在同一事务内创建规范UUID会话和绑定；任何一步失败均回滚，不留孤儿会话。
3. 唯一约束负责并发胜者；失败事务先回滚，再最多一次读胜者。锁/持久化不可确认时明确失败，不悄悄另建会话。
4. 幂等绑定不是会话处理锁。process仍保持US2非等待锁、持久RUNNING唯一约束、CAS、短事务审计及安全提交。
5. 修改任一身份维度得到不同UUID；比较大小写敏感、无trim归一化，分隔符合法但不参与拼接。
6. 旧UUID不新增绑定；显式--session未知、非法或Profile不匹配即拒绝，不回退新建。

## 只读库访问

SessionCatalog不得装配AuditStorageConfiguration、AuditSchemaInitializer或RuntimeRecoveryService。数据库/父目录/sidecar均需批准根、普通文件/链接数等既有路径契约；只打开已存在的安全sidecar，使用与写锁互斥的非创建读锁。缺锁或busy时拒绝，同JVM登记及跨进程OS锁同时生效，不释放别人的所有权。

只读SQLite连接启用`mode=ro`与`query_only`，只在规范V4库读取；拒绝未知schema，不建表/迁移。WAL/SHM需经真实驱动验证不产生写入，不能证明时固定QUERY_UNAVAILABLE；不使用immutable忽略WAL/锁。实现不得为“查询成功”退回读写或复制个人库。

测试比较目录清单、所有相关文件字节及mtime，预置RUNNING保持RUNNING，审计/序号/版本不变；缺库不建任何文件，活动写者不受干扰。运行状态只反映“本命令可确认的文件/锁/schema状态”，不是健康探活或认证授权。

## 数据与恢复证明

- 增量V4：完整schema与V3→V4migration各自存在，规范DDL/索引/外键/版本一致；伪V4、重复迁移、部分迁移均拒绝。
- 保存、关闭、重开比较完整消息/角色/次序/工具交换及审计关联，包括超过loadSession窗口的归档；不是只比列表size。
- 重开运行时允许既有RUNNING→INTERRUPTED恢复写入，但只读查询不允许；两者证据分开。
- 实际生产配置中查回持久数据，才能证明跨模块装配成功；mock或“Found N repositories”日志不足。

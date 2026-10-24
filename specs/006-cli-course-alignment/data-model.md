# 第18节 CLI：数据模型

设计状态，尚未执行DDL。三元组使用独立列，保留US2 UUID和分表，不构造拼接主键。

## 1. SessionIdentity（core值对象）

| 字段 | 约束 |
| --- | --- |
| channel | 非空，1～64个ASCII字符，匹配`[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}` |
| user | 非空，1～64个ASCII字符，匹配`[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`；逻辑标识，不作为认证授权 |
| profile | 非空，1～64个ASCII字符，匹配`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`，必须存在于启动Profile快照 |

全部字段大小写敏感，不trim、不折叠大小写、不自动补值；首尾空白非法。构造校验格式，Memory门面额外检查运行时已知密钥；原始身份不进入异常或诊断。CLI交互默认channel=`cli`、user=`local`，显式`--user`可替换user；这是工作区内逻辑分组，不是OS账号探测或多租户安全隔离。

不同三元组独立，例如`(a:b,c,p)`与`(a,b:c,p)`不可碰撞；使用列比较，不通过分隔符编码比较。

## 2. session_bindings（V4新增）

| 列 | 约束/含义 |
| --- | --- |
| channel | TEXT NOT NULL，值域同SessionIdentity |
| user_id | TEXT NOT NULL，值域同user |
| profile_name | TEXT NOT NULL，值域同profile |
| session_id | TEXT NOT NULL，规范UUID字符串，目标为既有sessions.id |
| created_at | TEXT NOT NULL，UTC ISO-8601，由运行时Clock产生 |

- 主键：`(channel,user_id,profile_name)`；`UNIQUE(session_id)`，本轮一个会话最多绑定一个三元组。
- 新增`UNIQUE INDEX`覆盖`sessions(id,profile_name)`，绑定表以`(session_id,profile_name)`复合外键引用，防止数据库级错归属。无级联删除。
- UUID、Profile与绑定一次短事务提交；唯一冲突必须回滚候选会话后重读既有绑定，最多一次受控冲突重读，失败固定安全错误；不做模型重试。
- 缺目标、Profile不匹配、非法存储内容或未知schema拒绝，不修复、不重绑。命中绑定无时间戳或版本写入；重开仍同UUID。
- 旧UUID会话没有绑定行，继续显式恢复；不推测用户或channel，不由第一次来访者认领旧会话。

## 3. 已有会话及历史（保留）

`sessions`的`id/profile_name/created_at/updated_at/version/next_sequence_no`语义不变；V3消息、处理、轮次、交换组、工具/Provider审计及关联均保留。Memory上下文窗口不等于完整存档；完整重开验收须独立逐页/全量读取确认。

本轮不新增`messages_json`。课程串行化/重开行为仍需证据；课程“真实列存在”断言明确为批准偏差，不能用行为等价掩盖。

## 4. SessionSummary / SessionQuery / SessionCatalog

- `SessionSummary`只含：UUID、Profile、createdAt、updatedAt、messageCount、processingCount；计数为非负long。不读正文、工具参数、用户身份、原始错误或凭据。
- `SessionQuery`：可选合法Profile过滤；limit默认20、范围1～100；offset默认0、范围0～100000。按`updated_at DESC,id ASC`稳定排序，返回page与hasMore，无无界列表。
- `SessionCatalog`只读端口，与Memory的写入口分离；CLI只消费安全投影，SQL留在storage。
- 缺库返回`DATABASE_NOT_INITIALIZED`状态；旧版库返回`MIGRATION_REQUIRED`；busy/缺既有安全sidecar/无法证明只读返回`QUERY_UNAVAILABLE`。不创建库/锁、不执行迁移或恢复，不将拒绝伪装空列表。
- 只读路径限制为现有规范V4库；V3及更早仍可离线迁移后查询。本轮不维持第二套历史schema查询器。

## 5. CLI元数据与Profile定义

- `CommandCapability`：固定命令路径、轻/重/只读查询分类、支持状态、原因、归属课程。不承载运行时秘密。
- `CliOptions`：工作区、Profile选择、可选显式UUID、interactive/batch、可选user、分页、serve本机端口；不包含消息/密钥参数。
- Profile新建目录名与name一致；参数仅显式Provider/model和安全名称，使用已验证模板生成profile.yml/AGENT.md/空skills目录，无YAML任意透传。字段内容及上限沿用005。
- 定义删除先完整检查目标、不跟随技能链接，原子移到agents外的`.aegisos/deleted-agents/<UUID>`；移动必须同一文件系统、不可覆盖、失败无部分删改。成功表示“不再加载”，不等于彻底销毁文件。无恢复命令/自动垃圾清理。
- 运行中的ProfileRegistry不变，创建/删除仅后续启动生效；旧会话仍绑定原Profile名称，不级联清除或迁移到别的Profile。以后同名定义是显式配置变更，不是自动换绑。

## 6. 状态与生命周期

```text
参数/帮助解析 → 轻操作或只读操作 → 关闭本次资源
                         或
                   chat运行时启动
                         ↓
                   读取一条有界输入
                 quit/EOF → 正常退出
                         ↓
       首条有效消息获取绑定或显式恢复UUID
                         ↓
           AgentService.process → 安全提交 → 输出
                         ↓
            交互下一条 / batch退出
```

仅交互路径可循环；任何输入/处理/持久化/输出失败非零结束，不自动重放。`/quit`只在interactive中识别；batch原文保持，不能误吞脚本正文。EOF在interactive中提交最后一条非空未换行消息，然后结束；空EOF零处理。单条超限立即拒绝，不无界读到换行；CRLF作为行分隔，不把尾部CR混入消息，单独CR不另解释控制指令。

## 7. V3→V4离线升级

1. 停止写运行时；复用同一sidecar独占契约；使用SQLite一致性backup，不单拷贝活动.db。
2. 校验V3版本、完整规范schema、外键/完整性、无活动写者；仅在临时副本演练。
3. 单事务新增绑定表/复合索引、更新aegisos_schema为4；原行与所有关联保持；失败回滚，重复执行拒绝。
4. 新进程重开校验完整V4 DDL；按既有恢复规则处理遗留状态，不重放任何外部副作用。
5. 对比旧表行数、内容投影、UUID、序号、审计关联与外键；再验证新三元组。回退用停机备份恢复，不能让旧V3程序读V4；禁止在个人库执行。

新空库直接V4；V1/V2必须先按已有离线迁移链到V3再到V4，无启动自动升级。旧V3建表/升级脚本与历史证据保持原样，测试以历史fixture保留。

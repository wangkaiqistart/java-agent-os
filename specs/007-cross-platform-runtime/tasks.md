# 跨平台运行实施任务

原006的76项与003/005/002历史保留。本轮用户已授权开始实现，不自动commit/push和真实外呼。

## 准备与契约

- [x] T001 保存工作树基线、建立007规格/计划/平台契约，记录SDS限制调整及首批本地文件系统范围。
- [x] T002 在core新增跨平台正文必成功与边界回归，在boot新增生产CLI两消息/重开必成功回归，记录macOS行为RED。

## US1：受控正文读取

- [x] T003 在core实现平台受控文件读取门面，Linux保留SDS路径与严格资源关闭。
- [x] T004 实现Darwin64目录FD/openat/fstat有界读取、身份复核与固定失败映射。
- [x] T005 实现Windows原生句柄读取、reparse/归属/共享模式/身份复核。
- [x] T006 将ContextLoader接入平台门面，保留UTF8/敏感/Bootstrap/Skill和预算语义。
- [x] T007 将既有测试的SDS平台拒绝分支升级为原生能力正向验收，Linux强制SDS仍保留；运行聚焦GREEN及适用打包。

## US2：存储、权限与路径

- [x] T008 在core实现POSIX/owner ACL私有文件/目录创建及Windows单链接身份检查。
- [x] T009 将RuntimeDatabaseLock/AuditStorageConfiguration接入，允许Windows盘符并拒绝URI/ADS/共享路径，保留独立锁/只读契约。
- [x] T010 将InitCommand/ProfileDefinitionService接入权限门面，补CRUD与私有对象回归。
- [x] T011 修复测试中的Unix临时目录/权限假设，保留所有安全断言；增加中文空格、CRLF、锁与关闭重开覆盖。
- [x] T012 完成生产CLI/存储聚焦GREEN与Maven打包，核验原V4/审计持久链路。

## US3：文档、CI与门禁

- [x] T013 配置三平台Java21构建/回归矩阵，提供平台原生进程与IDEA验证入口；默认不外呼。
- [x] T014 更新中文运行与Review指南、AGENT/README当前平台事实，保留旧验证历史。
- [x] T015 串行执行macOS clean package/quality、Linux强制SDS quality及受影响临时迁移/JAR探针，保存新鲜XML和退出码。
- [x] T016 执行当前Gitleaks与依赖安全门禁，新增依赖不得复用旧扫描结论。
- [x] T017 独立复审并追加修复任务，核对平台安全边界及生产装配，保留首个失败。
- [x] T018 一致性核验并记录实际完成/未执行的平台，Windows无真实执行证据时NOT_RUN，未完成项不勾选。

依赖：T001→T002→T003～T007→T008～T012→T013～T018。同一工作区Maven串行；Windows未执行不影响报告本机验证，但阻止宣称三平台全部验收。

## 独立复审追加任务（保留首轮发现）

- [x] T019 修复Windows Python3.13读取UTF-8模板/日志的默认编码依赖；脚本显式UTF-8，保留复审发现并复验macOS/Linux受影响进程探针。
- [ ] T020 在真实Windows NTFS环境执行新增CI或等价Java21全量门禁/CLI探针，获取DACL、软硬链接、锁及持久对话证据；当前无Windows执行环境，保持NOT_RUN，不能用双平台PASS替代。

- [x] T021 补充Windows原生/JDBC路径一致性回归，拒绝尾随点/空格和保留设备名；先RED后实现并重验双平台质量与独立边界复审。

# 实施计划：跨平台运行

## 技术背景

Java21、Maven九模块、Boot4.1.1/SpringAI2.0.1。增量基于未提交006，原始工作树快照.tools/cross-platform/baseline.tar.gz。保留当前分支，不通过切分支或清理覆盖已有修改。

## 宪法核查

保持九模块、显式路由、统一Memory和Sandbox、审计前置、安全凭据和每故事测试/打包。新增平台端口置于core的platform包，不依赖Spring/JPA/Provider；原生实现依赖固定JNA5.17.0（本地已有，需本轮依赖门禁验证），不增加模块或Java预览参数。生产调用方仅访问平台门面。新依赖与平台代码单独review。

## 方案与研究结论

1. Linux继续JDK SecureDirectoryStream/NOFOLLOW_LINKS；将目录描述符读取提取至平台门面。
2. macOS使用libSystem open/openat/fstat/read/close：持有批准目录FD，逐级O_DIRECTORY/O_NOFOLLOW，末端O_NOFOLLOW/O_NONBLOCK，fstat确认普通文件、有界读前后复核描述符身份；Darwin64位stat布局显式记录并通过真实文件属性交叉验证，未知ABI拒绝。
3. Windows使用Kernel32文件/目录句柄，拒绝reparse点，读取前GetFileInformationByHandle/GetFinalPathNameByHandle核验真实归属，持有目录句柄并禁止共享删除，末端句柄禁止共享写入；有界ReadFile后复核。单硬链接数由句柄信息取得，不伪造unix属性。
4. 新文件/目录采用POSIX权限或owner ACL。Windows drive路径合法，但URI、ADS、UNC路径拒绝。数据库schema不变。
5. 实际生产装配测试在macOS由拒绝分支提升为正向；Linux继续强制SDS。Windows CI先准备，不把未运行结果写PASS。

## 文件和模块

core/platform：SecureFileAccess门面、Darwin/Windows原生实现、OwnerOnlyFiles权限辅助。
core/context：ContextLoader改用平台受控读取，不改变敏感/UTF8及预算门面。
storage：RuntimeDatabaseLock单链接调用平台门面；AuditStorageConfiguration合法路径与私有目录。
cli/boot：init与Profile CRUD使用跨平台权限辅助；CLI参数路径测试平台化。
.github/workflows、scripts、docs/engineering：三平台验证与IDEA指南。

## 验证

先新增macOS正文/生产CLI两处理必成功回归并确认RED，再实现。聚焦GREEN后串行macOS clean package/quality与Linux强制SDS全量quality。跑回环JAR交互/持久查询/CRUD、临时迁移、gitleaks及本轮依赖门禁。无真实模型外呼。独立review重点native ABI、Windows句柄生命周期、边界/硬链接/锁、权限及前置审计。

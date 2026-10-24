# 007 跨平台变更、重点 Review 与验证

## 变更与阅读顺序

原先正文读取仅接受 JDK SDS，macOS 对话在 Provider 发送前返回 CONTEXT_FILE_UNAVAILABLE；Windows 还被 unix:nlink、POSIX 权限和全盘符拒绝阻断。007 保留九模块与 V4 数据格式，增加平台文件门面，使相同 CLI/AgentService/MemoryService 装配可使用原生系统能力。Windows 实现待实际 CI/实机验证，不用 macOS/Linux 的 PASS 替代。

| 顺序 | 文件/模块 | 阅读重点 |
| --- | --- | --- |
| 1 | core/context/ContextLoader | 原有批准根、Skill 启动目标、正文重读、容量/UTF-8/敏感门槛 |
| 2 | core/platform/SecureFileAccess | SDS、Darwin、Windows 分派；固定失败、有界字节和单链接端口 |
| 3 | core/platform/DarwinFiles | Darwin LP64 stat 与 JDK 属性交叉验证、openat/NOFOLLOW、FD 逆序关闭 |
| 4 | core/platform/WindowsFiles | 本地 NTFS、reparse拒绝、目录句柄、正文共享模式、身份复核 |
| 5 | core/platform/WindowsPrivateFiles、OwnerOnlyFiles | POSIX 700/600、初始受保护 owner DACL、合法本地数据库路径 |
| 6 | storage/runtime/RuntimeDatabaseLock、audit/AuditStorageConfiguration | 原sidecar生命周期锁接线、单链接、合法盘符、既有文件零权限变更 |
| 7 | cli/InitCommand、boot/cli/ProfileDefinitionService、boot/config/ProfilePathPolicy | 私有创建、原子删除、绑定元数据跨平台斜杠 |
| 8 | scripts/verify-cli-runtime.py、.github/workflows/engineering.yml | 新鲜JAR、回环HTTPS、审计重开、三平台矩阵和真实状态 |

core 平台包不依赖 Spring/JPA/Provider，新增固定 JNA 5.17.0 负责稳定原生 ABI 访问，不需要 Java 预览参数。其他领域代码只调用平台门面。全局/Profile 启动快照、正文逐轮重读、显式路由、重试、审计前置、Sandbox、无明文泄露及末轮工具行为保持原契约。

## 重点 Review

- **打开前后身份**：目录/软链接替换不能跨越批准根；Darwin 不凭路径普通读取，stat偏移必须和真实Java元数据匹配；Windows GetFinalPathName/volume/index/size/time/reparse及共享标志均需核对。
- **资源所有权**：每次 native/SDS 调用拥有自己的句柄，失败/超限也逆序关闭；WinAPI 返回值、失败句柄和64位布局不能被默认为成功。
- **锁不能弱化**：数据库与sidecar拒绝硬链接；JVM先认领再打开FileChannel；生命周期只锁独立sidecar，不锁SQLite inode；失败竞争不能释放当前持有者的锁。
- **权限创建即生效**：Windows D:P 阻止父ACL合入，Unix属性在CREATE_NEW时给出；既有库/配置不改权限、不截断。Profile删除保留ATOMIC_MOVE，不增加递归删除回退。
- **Windows真实路径**：盘符合法但URI、ADS、UNC和映射网络卷拒绝；Skill元数据用 `/`，软链接权限不够必须报告失败，不能把junction放行。
- **零副作用与前置门槛**：非法正文/敏感值必须零模型发送；只读session查询不初始化/恢复/写权限；默认测试不访问真实模型。
- **验证不虚报**：双平台本地结果、Windows待运行、PTY与显式交互、Linux strace系统调用证据分别记录。新增依赖必须用本轮安全扫描。

独立审查采用 reviewing-code 的指定文件 Standard 流程，覆盖安全、健壮性、需求、维护、风格及依赖；首个发现与修复记录见 [007 validation](../../specs/007-cross-platform-runtime/validation.md)，本文件不是独立审查结果的替代品。

## 验证入口

```bash
mvn -B -pl aegisos-core test
mvn -B clean package
mvn -B clean verify -Pstatic-analysis
# Linux还必须证明真实SDS能力：
mvn -B clean verify -Pstatic-analysis -Daegisos.test.require-secure-directory-stream=true
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

新增 CrossPlatformContextTest 和 CrossPlatformChatTest 必须成功，不接受平台拒绝替代。PlatformFilesTest 验证有界读取、关闭后删除、越界/链接/硬链接和私有对象；InitPermissionsTest 验证实际生成文件权限；既有RuntimeDatabaseLockTest继续使用独立JVM证明锁持有与释放。

打包探针在 Bash/PowerShell 都可用（替换时间为本轮构建前UTC锚点）：

```text
python scripts/verify-cli-package.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after <本轮UTC时间> --allow-no-strace
python scripts/verify-cli-runtime.py --jar aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar --built-after <本轮UTC时间>
```

需要 Python3、JDK keytool、OpenSSL；Windows CI 可使用Git附带OpenSSL。回环证书只进入子进程truststore，脚本不改系统信任。Linux安装strace时去掉allow-no-strace，证明离线命令零IP联网/监听；macOS/Windows没有strace时不宣称系统调用证明。Unix额外PTY，Windows显式交互；均检查两处理同会话、成功审计、关闭重开、Profile CRUD、只读文件不变、serve停止释放锁与gateway未启用。

手动IDEA与YAML配置见 [三平台运行指南](cross-platform-runtime.md)。迁移继续只在临时副本运行现有verify-react-migration/verify-cli-migration；不修改V1～V4历史SQL。实际命令、数量、退出码与尚未执行范围统一见 [007验证](../../specs/007-cross-platform-runtime/validation.md)。

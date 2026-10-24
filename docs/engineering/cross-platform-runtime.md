# 三平台运行与 IDEA 调试

007 将正文读取从仅 Linux 的 SecureDirectoryStream 扩展为平台受控文件访问。Linux 使用 SDS；macOS 使用 Darwin 目录 FD/openat；Windows 使用原生 NTFS 句柄。没有关闭安全校验的配置项。实际已执行平台与门禁见 [007 验证记录](../../specs/007-cross-platform-runtime/validation.md)，Windows 在获得 CI 或实机证据前保持 NOT_RUN。

## 环境与目录

安装 64 位 Java 21，IDEA 的 Project SDK、Maven Importer/Runner JRE 都选择 21。Maven 在仓库根执行 `mvn clean package`。同一个可执行 JAR 位于 `aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar`。

工作区是运行数据目录，可在仓库以外。macOS 推荐本地 APFS；Linux 使用本地文件系统；Windows 当前适配本地 NTFS 和正常盘符路径。不要使用 UNC、映射网络盘、SQLite URI 或 ADS；Windows路径分量不能以点/空格结尾，也不能使用NUL/CON/COM1等保留设备名，避免JDBC与扩展原生路径产生别名差异。SQLite 库与 `.lock` 必须为单硬链接普通文件，同库仅一个运行时持有独立锁。已有 V1/V2/V3 库仍需停机备份和显式离线迁移，本轮没有迁移个人库。

## 在 IDEA 中运行

1. 打开根 `pom.xml` 并导入 Maven，选择 Java 21，等待依赖下载。
2. 新建 **Application** Run Configuration：Main class 为 `com.aegisos.boot.AegisOsApplication`，Use classpath of module 选择 `aegisos-boot`，Working directory 为仓库绝对路径。
3. 创建一个空的本地工作区目录，例如 macOS `/Users/你的用户名/aegisos-work`、Linux `/home/你的用户名/aegisos-work`、Windows `C:\aegisos-work`。
4. Program arguments 填入 `init --workspace "/绝对路径/aegisos-work"`，运行一次。Windows 用 `init --workspace "C:\aegisos-work"`。输出 INITIALIZED 后即退出；重复 init 会拒绝覆盖。
5. 复制这个 Run Configuration，Program arguments 改为 `chat --workspace "/绝对路径/aegisos-work" --profile weather --interactive`。IDEA 的控制台不保证被识别为终端，因此明确加 `--interactive`。
6. 在该配置的 Environment variables 中设置 `DEEPSEEK_API_KEY`。不要把值写进源码、YAML、共享的 `.run`/`.idea` 配置或截图。
7. VM options 可以填 `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`，IDEA Console Encoding 选择 UTF-8。启动后逐行输入消息，输入 `/quit` 正常退出；再次启动复用同一个 CLI/local/Profile 会话。

推荐断点顺序：`AegisOsApplication` → `ChatCommand.runInteractive` → `AgentService` → `PromptBuilder`/`ContextLoader` → `SecureFileAccess` → `ReActLoop`。macOS 会进入 `DarwinFiles`，Windows 进入 `WindowsFiles`，Linux 进入 SDS 实现。配置调试与正文读取不需要为 IDEA 更换安全实现。

## 配置怎么改

初始化生成的唯一全局入口是 `<workspace>/.aegisos/config.yml`。保留其他配置，仅按需编辑 Provider 项：

```yaml
aegisos:
  providers:
    - name: deepseek-main
      adapter: openai-compatible
      models: [deepseek-flash]
      base-url: https://api.deepseek.com
      credential-ref: env:DEEPSEEK_API_KEY
      thinking-mode: disabled
      max-retries: 0
      connect-timeout: 5s
      read-timeout: 60s
```

这是配置片段，不是覆盖整个 config.yml 的完整文件。模型名称必须是账户实际可用且在 `models` 中允许的值。`credential-ref` 保存环境变量引用。Agent 配置在 `.aegisos/agents/weather/profile.yml`，其中 `provider.name/model` 必须与全局定义一致。修改全局配置、Profile、凭据或技能绑定后重启；修改 `AGENT.md`、Bootstrap 和已绑定的 `SKILL.md` 正文在下一轮组装时重读。

手动 chat 会产生真实模型调用和费用。自动 Maven/CI 和下述验证脚本只使用临时库与回环替身。初次手测可问“请用一句中文介绍自己”，确认模型、持久会话和退出行为；天气工具还需要明确配置 Sandbox 白名单，沿用 [US2 运行指南](us2-runtime.md)，默认 targets 为空不会放行外网工具。

## 命令行示例

macOS/Linux：

```bash
mvn -B clean package
CLI_JAR="$PWD/aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar"
CLI_WORK="$HOME/aegisos-work"
mkdir -p "$CLI_WORK"
java -jar "$CLI_JAR" init --workspace "$CLI_WORK"
java -jar "$CLI_JAR" provider list --workspace "$CLI_WORK"
java -jar "$CLI_JAR" chat --workspace "$CLI_WORK" --profile weather --interactive
java -jar "$CLI_JAR" session list --workspace "$CLI_WORK"
```

PowerShell：

```powershell
mvn -B clean package
$cliJar = Join-Path $PWD 'aegisos-boot/target/aegisos-boot-0.1.0-SNAPSHOT.jar'
$cliWork = 'C:\aegisos-work'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $cliWork -Force | Out-Null
java -jar $cliJar init --workspace $cliWork
java -jar $cliJar provider list --workspace $cliWork
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar $cliJar chat --workspace $cliWork --profile weather --interactive
java -jar $cliJar session list --workspace $cliWork
```

运行 chat 前在当前进程环境或 IDEA 中注入凭据。PowerShell 先设置上述控制台 UTF-8，再使用显式交互；stdout/stderr属性只控制输出，不能替代输入编码设置。避免旧版 PowerShell 管道的默认文本编码影响严格 UTF-8 输入。所有命令及退出码见 [CLI 指南](cli-usage.md)。init/help/配置查看不需要模型凭据。

## 安全与平台边界

正文保持批准根、普通文件、有界读取、严格 UTF-8、敏感内容拒绝和身份复核；macOS 使用 NOFOLLOW 目录 FD，Windows 拒绝 reparse 点并持有禁止共享删除的目录句柄、禁止共享写和删除的正文句柄。Windows 私有对象创建时使用受保护 owner DACL，Unix 使用目录 700/文件 600；已有对象不修正权限。身份、API、原生库或 ABI 无法验证时仍返回固定失败，不走普通路径回退。

Skill 仍是公共 `.aegisos/skills` 内的相对软链接绑定。Windows 创建软链接通常需要 Developer Mode 或管理员授予的权限；普通无 Skill 的 chat 不需要该权限。junction 不作为 Skill 绑定。CI 对软链接能力先预检，失败会阻断，不跳过安全断言。

本地工作区由可信管理员维护；这些检查不是防御同等 OS 权限恶意管理者的沙箱。网络共享、32位 JVM、其他 Windows 文件系统未纳入支持范围。Windows 自动终端识别仍需实际终端验证，自动脚本通过显式 `--interactive` 验证其对话链路；Unix 额外用 PTY 检验真实终端模式。

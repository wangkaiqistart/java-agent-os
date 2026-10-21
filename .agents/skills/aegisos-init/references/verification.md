# 初始化验证清单

只有真正执行成功才能标 PASS。检查结果写入初始化报告，NOT_RUN/N/A 必须说明原因。

## 开始前

- [ ] 确认绝对仓库路径、实际 Git 分支、Java 21 和 Maven 版本；确认 POM 位置。
- [ ] 记录现有 dirty 文件，不覆盖外部/用户的改动；确认适用宪法、活动 spec 与本次范围。
- [ ] 识别已有测试、CI、质量工具和被跟踪构建物；只补缺项，不重复生成模块。
- [ ] 修改公共接口之前，确保本次基础工程规格已覆盖；不覆盖 US-1 的 plan/tasks 或擅自重置活动元数据。

## 配置及实际行为

- [ ] effective POM 中质量插件真正启用，Jupiter 测试被 Surefire 执行，版本不是快照或未展开变量。
- [ ] Web 基础 Bean 在真实启动上下文生效，不只是在源码中存在。
- [ ] 正常/错误请求返回合法 requestId；异常、并发请求和复用线程均不泄漏上一请求的 MDC。
- [ ] 非法参数和内部异常返回预期 HTTP 状态/稳定错误码，不含原始异常、栈或秘密。
- [ ] health 可访问且不泄漏详情；未批准的 env/configprops/heapdump 等管理端点不可访问。
- [ ] Prometheus 只有批准启用时才可访问；新建 registry 之外的采集部署未完成应单列。
- [ ] 针对选定 Header/请求体/超时配置有真正的边界测试；不能用参数校验替代字节大小限制。
- [ ] 日志和扫描报告无秘密哨兵/请求正文；格式、静态分析、秘密和依赖扫描分别记录结果。

## 命令顺序

从仓库根执行；不在根目录时给 Maven 显式 `-f` 绝对路径。以下命令以已合并 Maven 模板的工程为前提，模板尚未使用时不能直接宣称这些入口存在。

```bash
java -version
mvn -version
mvn help:effective-pom -Doutput=target/effective-pom.xml
mvn spotless:check
# 先按受影响模块运行现有/新增聚焦测试，再执行完整门禁
mvn test
mvn clean package
bash scripts/check.sh quality
bash scripts/install-gitleaks.sh
bash scripts/check.sh secrets
bash scripts/check.sh dependencies
```

`gitleaks git` 检查 Git 历史，`gitleaks dir` 检查工作目录；结合已安装版本的 CLI 语法与忽略规则，不只扫描已提交历史就声称本次未提交文件也安全。历史命中与本次新增命中分别报告，未经用户授权不重写历史。

脚本路径是本仓库原生入口；其他仓库若还不存在，先创建等价入口再执行。聚合依赖扫描先完成 reactor package，再调用 `org.owasp:dependency-check-maven:aggregate`，避免父 POM 先 verify 时子模块尚未构建；security Profile 提供扫描配置，不将 aggregate 绑在父模块的 verify 阶段。设置 NVD key 时只引用环境变量，缺失时不传空密钥。

扫描命令执行前确认工具、profile、NVD 数据源存在。纯扫描阶段使用 check 不使用 format apply；必要时先限定变更文件修复格式，再完整复核。首次获取漏洞数据可能耗时，使用可轮询进程并更新进度，不把网络错误当安全通过。历史秘密扫描失败后仍应完成工作目录扫描，两项任何一项失败都保持非零状态。

检查 Surefire 报告的实际测试类/数量和失败数；骨架中零测试的 BUILD SUCCESS 只证明构建，不证明行为。不要加 skipTests、禁用安全审计或用 failIfNoTests 绕过新增行为的测试。

## 启动演示

优先使用随机端口的 Boot 集成测试证明 health/错误响应/请求 ID。需要手动演示时，在临时工作目录和 loopback 上运行已打包 JAR，参数选空闲端口并记录 PID；不启用未知真实 Provider/调度任务。测试结束停止本次进程。

实际 jar 名称从 boot POM/target 读取，不硬编码旧版本。用 health 验证服务已就绪，再演示 requestId 和一项受控错误；只有具体业务端点存在时才演示对应 API。

## 完成判据

- [ ] 列明最终改动文件、已执行命令、退出码、测试数量和报告位置。
- [ ] 常规门禁与实际新增行为全部通过；安全检查失败/未运行时不写“安全初始化完成”。
- [ ] 活动 US-1 元数据、未授权模块和原有 dirty 修改保持原意；本次进程已停止。
- [ ] 幂等复核：再次运行不重复添加依赖、插件、过滤器或 CI job，不重建模块。
- [ ] 区分“Skill 已编写”“工程配置已修改”“测试/打包通过”“启动演示通过”和“真实外部集成验证”。

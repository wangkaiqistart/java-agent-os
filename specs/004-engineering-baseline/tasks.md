# 初始化任务

- [x] I001 审查并修正 aegisos-init 的执行入口、格式迁移和安全失败边界。
- [x] I002 合并根 Maven 门禁，补 web/boot 依赖与配置。
- [x] I003 实现请求关联、安全错误与 JSON 大小限制；编写行为测试。
- [x] I004 配置健康、dev OpenAPI、本机 Prometheus 与实际 HTTP 验证。
- [x] I005 增加共享脚本、GitHub CI、忽略规则与开发约定。
- [x] I006 执行完整构建、静态与秘密扫描、依赖扫描及启动演示。
- [x] I007 更新执行报告、复核幂等配置和原始修改保留。

- [x] I008 本地依赖安全验收：Boot 4.1.1 / Framework 7.0.9 / Tomcat 11.0.27 下原生门禁通过，61 个依赖条目无漏洞匹配。
- [ ] I009 GitHub CI 实际执行：本轮 `gh auth status` 检查发现当前账号凭据无效；本地变更尚未发布，未触发远端工作流，保持 NOT_RUN。需维护者恢复登录并发布待验证代码后执行。

I006 表示已执行并记录扫描结果，不表示外部漏洞扫描通过；详见 `docs/engineering/initialization.md`。

## 2026-10-21 依赖安全修复增量

- [x] S001 核对官方公告与已发布版本，确认 Log4j 组件误匹配；快照误置 target 导致被 clean 清除，恢复限制须写入报告。
- [x] S002 升级 Boot BOM，集中覆盖兼容的 Tomcat、Log4j、Commons Lang 与 Swagger UI 版本。
- [x] S003 验证打包版本、Swagger 资源与既有 HTTP/日志/监控行为；quality/secrets 通过，dependencies 已执行并因 12 个 Framework 阻断匹配退出 1。
- [x] S004 对比扫描结果并同步文档；记录商业补丁/大版本迁移边界与构建物恢复限制，37 个原先未修改的构建文件已恢复，10 个原先编译变化路径保留重新生成内容。


## Boot 4 迁移增量

- [x] M001 按用户明确授权迁移 Boot 4.1.1 / Framework 7.0.9 / springdoc 3.1.1，保留 Java 21 与九模块。
- [x] M002 迁移 Jackson 3、MVC/Actuator 测试装配和指标注解；显式保留最小 health 响应。
- [x] M003 核对官方公告，修复 BOM 默认 Tomcat 11.0.24 告警，core/el/websocket 统一至 11.0.27。
- [x] M004 原生 quality/secrets/dependencies 通过，17 项测试成功，依赖分析 61 个条目无匹配；复核生产 JAR 版本与测试类隔离。
- [x] M005 同步当前指南和 US-1 plan/research/tasks，AI 2 SDK、JPA 与 SQLite 组合留给对应业务实施验证。


## 本轮 speckit-implement 收敛

- [x] R001 显式解析 `004-engineering-baseline` 并保留活动功能元数据；该功能无独立 checklists，扩展 hook 未配置。
- [x] R002 默认/dev/metrics 应用配置及 Provider 测试 Profile 改为 YAML；保持原有配置键与安全边界，补 Web 基础代码中文注释。
- [x] R003 移除 `AGENT.md` 中的明文模型凭据，改为环境变量引用；保持真实秘密不进入新增文档与生成物。
- [x] R004 按当前含 US-1 的工作区重新验证 quality/dependencies/secrets、可执行 JAR、配置与快照恢复，记录到 validation.md 与初始化报告。

I009 是远端验证遗留，不以本地命令通过替代，不将本功能标为全部任务完成。

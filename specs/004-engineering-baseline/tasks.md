# 初始化任务

- [x] I001 审查并修正 aegisos-init 的执行入口、格式迁移和安全失败边界。
- [x] I002 合并根 Maven 门禁，补 web/boot 依赖与配置。
- [x] I003 实现请求关联、安全错误与 JSON 大小限制；编写行为测试。
- [x] I004 配置健康、dev OpenAPI、本机 Prometheus 与实际 HTTP 验证。
- [x] I005 增加共享脚本、GitHub CI、忽略规则与开发约定。
- [x] I006 执行完整构建、静态与秘密扫描、依赖扫描及启动演示。
- [x] I007 更新执行报告、复核幂等配置和原始修改保留。

- [ ] I008 在受控环境配置 NVD key 并打通 NVD/RetireJS 数据源后重跑依赖安全扫描；当前 FAIL，未完成安全验收。GitHub CI 实际执行待推送触发。

I006 表示已执行并记录扫描结果，不表示外部漏洞扫描通过；详见 `docs/engineering/initialization.md`。

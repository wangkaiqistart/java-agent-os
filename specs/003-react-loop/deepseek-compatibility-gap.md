# US2 真实验收后的 DeepSeek 兼容缺口

本文件保留首次已授权验收的FAIL及其决策依据。用户随后批准A路线，显式关闭thinking的Java与验收配置适配已实现，工程验证结果见validation.md；新版已获新授权执行并PASS（3模型发送/1GET、零重试），首次FAIL不变。

**后续批准（2026-10-22）**：用户选择A，显式配置关闭thinking，先完成课程范围；暂不支持thinking工具续轮。下方保留原比较作为决策依据，B不实施。新增FR-039/SC-012/A16及T136～T141，总任务141项；原失败历史不变，新版已于2026-10-22执行一次新授权真实验收并PASS。具体字段与拒绝策略见contracts/configuration-cli.md。

## 首次真实失败时已证实的事实

- 本轮同一会话实际2次模型发送/1次天气GET，零重试。模型首轮和天气工具SUCCEEDED，工具回填后的第二次Provider审计为FAILED/INVALID_REQUEST；用户处理FAILED，追问未执行。
- 首次失败时ModelMessage/ModelResponse只携带标准文本/工具信息；OpenAiMessageMapper没有保留或回传reasoning_content，客户端也没有显式关闭thinking。
- [DeepSeek官方Thinking Mode文档](https://api-docs.deepseek.com/guides/thinking_mode)当前描述默认启用thinking；携带tools时需要回传相关reasoning_content，缺失可返回400。
- 原始厂商错误正文按既有敏感保护未保存。因此“缺少reasoning_content”是有源码/官方协议依据的兼容缺口，与本次错误一致；本轮证据尚不足以确定它是唯一根因。不得补写未观察到的HTTP状态或厂商原话。

## 当时比较的两条修订路线（已选择A）

| 路线 | 具体改动与验收影响 |
| --- | --- |
| A：显式非thinking课程验收 | 在受校验的Provider/冒烟YAML中明确声明非thinking模式，由适配层按显式选项生成DeepSeek请求扩展；禁止按provider名称猜测、默认静默关闭或换模型。保留相同deepseek-flash、天气事实断言、预算和前置审计。不引入reasoning正文的持久化；文档明确本轮未支持thinking工具续轮。需补配置解析/映射/真实wire请求的离线回归，更新指纹/契约后重过工程门禁。 |
| B：支持thinking工具续轮 | 扩展公共消息/响应、mapper、Memory/Session存储与历史投影，完整保留必要reasoning_content并在工具续轮回传；配置/迁移/恢复契约同步修订。该内容必须纳入敏感拒绝、UTF-8容量和完整组裁剪，不写操作审计、日志或导出报告。补含thinking字段的真实协议替身及恢复/裁剪/秘密反例；不能只在测试harness补字段冒充生产支持。 |

A保持课程范围，现已按用户选择实现；B扩大协议与存储范围，本轮不实施。原135项编号与首次失败历史保留，新增六项承载批准的修订。当前实现不保留或回传reasoning_content，不宣称支持thinking工具续轮。

## 后续执行边界

本次一次真实授权已执行完毕。先完成所选路线的规格/实现/离线与工程验证，再准备新的唯一runId和可审查配置，才请求新的真实执行授权；不自动复用本次配置/凭据进行第二轮。该执行边界已按新授权履行，新的真实PASS证据见下节；后续任何真实运行仍须独立明确启用。不得把本次天气GET成功、HTTP调用发生或旧Provider冒烟PASS视为A14通过。

## 批准A路线的真实复验结果

run-id react-20261022-184544-25261963；Maven=0、独立IT=1且零失败/错误/跳过、verifier=0。同会话两处理COMPLETED，接受轮次2/1，实际3模型发送/1天气GET、零重试，事实布尔断言与只读重开审计全部通过。旧FAIL的6个证据文件SHA256逐一保持不变。该结果支持显式disabled的本轮课程范围，不反证首次错误的唯一根因，也不支持thinking工具续轮。T133～T135已完成，详见validation.md。

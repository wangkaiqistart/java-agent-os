package com.aegisos.boot.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 仅显式 Maven profile 发现本类；任何条件不满足都失败退出，绝不以跳过冒充验收。 */
@Tag("real-provider")
class ProviderSmokeIT {
  @Test
  void providerRoundTrip() throws Exception {
    String run = UUID.randomUUID().toString();
    System.setProperty("aegisos.smoke.run-id", run);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("runId", run);
    evidence.put("startedAt", Instant.now().toString());
    evidence.put("status", "NOT_RUN");
    evidence.put("stage", "PREFLIGHT");
    evidence.put("reason", "CONDITIONS_MISSING");
    evidence.put("httpSendAttempts", 0);
    evidence.put("responseNonEmpty", false);
    evidence.put("auditVerified", false);
    evidence.put("reopenedAuditVerified", false);
    evidence.put("newAuditRows", 0);
    evidence.put("providerName", ProviderSmokeFixture.PROVIDER);
    evidence.put("modelName", ProviderSmokeFixture.MODEL);
    evidence.put("sessionId", ProviderSmokeFixture.SESSION);
    // 指纹仅覆盖固定非秘密协议约束；不读取或哈希模型密钥。
    evidence.put(
        "configurationFingerprint",
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        "deepseek-main|deepseek-flash|https://api.deepseek.com/v1|retries=0|tools=0|temperature=omitted"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    ProviderSmokeFixture fixture = null;
    try {
      // 先验证打包后的Failsafe运行类路径；即使缺凭据，也不能把类加载故障误判为缺条件。
      // 使用名称加载，不读取环境、不装配连接、更不发模型请求。
      for (String type :
          List.of(
              "com.aegisos.boot.config.SafeProfileYamlReader",
              "com.aegisos.boot.config.ProviderConfiguration",
              "com.aegisos.boot.config.ProfileConfiguration",
              "com.aegisos.storage.audit.AuditStorageConfiguration")) Class.forName(type);
      var config =
          ProviderSmokeFixture.preflight(
              Boolean.getBoolean("aegisos.smoke.enabled"), System.getenv());
      fixture = new ProviderSmokeFixture(config);
      evidence.put("stage", "CALL");
      evidence.put("status", "FAIL");
      evidence.put("reason", "MODEL_OR_AUDIT_CHECK_FAILED");
      var response = fixture.call(config);
      evidence.put("requestId", response.requestId().toString());
      boolean text = response.content() != null && !response.content().isBlank();
      boolean audit = fixture.auditMatches(response.requestId());
      fixture.close();
      boolean reopened = fixture.auditMatches(response.requestId());
      evidence.put("responseNonEmpty", text);
      evidence.put("auditVerified", audit);
      evidence.put("reopenedAuditVerified", reopened);
      evidence.put("newAuditRows", audit ? 1 : 0);
      assertTrue(
          fixture.sends.get() == 1 && text && audit && reopened,
          "FAIL:MODEL_OR_AUDIT_CHECK_FAILED");
      evidence.put("status", "PASS");
      evidence.put("stage", "COMPLETE");
      evidence.put("reason", "OK");
    } catch (ProviderSmokeFixture.NotRun e) {
      fail("NOT_RUN:CONDITIONS_MISSING");
    } catch (ClassNotFoundException | LinkageError e) {
      evidence.put("status", "FAIL");
      evidence.put("stage", "SETUP");
      evidence.put("reason", "HARNESS_UNAVAILABLE");
      fail("FAIL:HARNESS_UNAVAILABLE");
    } catch (RuntimeException e) {
      evidence.put("status", "FAIL");
      evidence.put("reason", "MODEL_OR_AUDIT_CHECK_FAILED");
      fail("FAIL:MODEL_OR_AUDIT_CHECK_FAILED");
    } finally {
      if (fixture != null) {
        evidence.put("httpSendAttempts", fixture.sends.get());
        fixture.close();
      }
      evidence.put("completedAt", Instant.now().toString());
      Path target = Path.of("target/provider-smoke-result.json");
      Files.createDirectories(target.getParent());
      JsonMapper.builder().build().writeValue(target.toFile(), evidence);
      // Failsafe 独立 fork 保留安全 run-id 供 XML 与 JSON 关联，进程退出后自然清理。
    }
  }
}

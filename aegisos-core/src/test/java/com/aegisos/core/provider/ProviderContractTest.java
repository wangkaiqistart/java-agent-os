package com.aegisos.core.provider;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderContractTest {
  @Test
  void invalidRequestsRemainAuditableAndListsAreSnapshots() {
    var messages = new ArrayList<ModelMessage>();
    messages.add(null);
    var request =
        new ModelRequest(null, null, messages, null, new GenerationOptions(Double.NaN, -1));
    messages.clear();
    assertThat(request.messages()).hasSize(1);
    assertThat(request.providerName()).isNull();
    assertThatThrownBy(() -> request.messages().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullableUsageIsNotInvented() {
    assertThat(new TokenUsage(null, 2L, null).totalTokens()).isNull();
    assertThatThrownBy(() -> new TokenUsage(-1L, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namesAreBoundedAndNotTrimmed() {
    assertThat(SafeProviderMetadata.provider("A-1.x")).isEqualTo("A-1.x");
    for (String name : List.of(" a", "a\n", "https://a", "a".repeat(65)))
      assertThat(SafeProviderMetadata.provider(name)).isNull();
    assertThat(SafeProviderMetadata.model("org/model:1")).isNotNull();
    assertThat(SafeProviderMetadata.model("x".repeat(129))).isNull();
  }

  @Test
  void errorsDoNotRetainCausesOrBodies() {
    var error =
        new ProviderException(
            new ProviderFailure(
                UUID.randomUUID(),
                ProviderErrorCode.PROVIDER_TIMEOUT,
                "p",
                "m",
                1,
                true,
                ExternalOutcome.UNKNOWN));
    assertThat(error.getCause()).isNull();
    assertThat(error.getMessage()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(ModelRequest.text("p", "m", "private-body").toString())
        .doesNotContain("private-body");
  }

  /** 保留五字段请求以及 V1 审计构造器，防止补齐功能破坏已有调用方。 */
  @Test
  void legacyConstructorsStaySourceCompatible() {
    var id = UUID.randomUUID();
    var at = java.time.Instant.now();
    assertThat(new ModelRequest("p", "m", List.of(), List.of(), null).modelName()).isEqualTo("m");
    assertThat(new com.aegisos.core.audit.AuditStart(id, 1, "p", "m", at).requestId())
        .isEqualTo(id);
    assertThat(
            new com.aegisos.core.audit.AuditRejection(
                    id, "p", "m", at, ProviderErrorCode.INVALID_REQUEST)
                .errorCode())
        .isEqualTo(ProviderErrorCode.INVALID_REQUEST);
  }

  @Test
  void publicContractsHaveNoFrameworkTypes() {
    for (var type :
        List.of(
            ModelRequest.class,
            ModelResponse.class,
            ProviderService.class,
            com.aegisos.core.audit.LlmCallAuditStore.class))
      for (var m : type.getDeclaredMethods())
        for (var param : m.getParameterTypes())
          assertThat(param.getName())
              .doesNotStartWith("org.springframework")
              .doesNotStartWith("jakarta.persistence");
  }
}

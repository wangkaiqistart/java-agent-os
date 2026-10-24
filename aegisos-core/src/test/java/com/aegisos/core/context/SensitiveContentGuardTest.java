package com.aegisos.core.context;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

class SensitiveContentGuardTest {
  private final SensitiveContentGuard guard =
      new DefaultSensitiveContentGuard(List.of("synthetic-secret-react-only-123456"));

  @Test
  void detectsKnownCredentialAfterJsonUnicodeDecoding() {
    String json =
        "{\"url\":\"https://example.invalid/" + "\\u0073" + "ynthetic-secret-react-only-123456\"}";
    assertThatThrownBy(() -> guard.checkJson(json)).hasMessage("SENSITIVE_CONTENT").hasNoCause();
  }

  /** 字段名的标准 JSON 转义不能让快照外的实际值绕过明确敏感载体检查。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"api\\u005fkey\":\"external-live-value\"}",
        "{\"nested\":[{\"pass\\u0077ord\":\"external-live-value\"}]}",
        "{\"to\\u006ben\":\" external live value \"}",
        "{\"se\\u0063ret\":12345}",
        "{\"se\\u0063ret\":true}",
        "{\"pass\\u0077ord\":\"null hunter2\"}",
        "{\"to\\u006ben\":\"env:REFERENCE actual-value\"}",
        "{\"api\\u005fkey\":\"${TOKEN} actual-value\"}",
        "{\"se\\u0063ret\":\"\\\"actual-value\"}",
        "{\"se\\u0063ret\":\"'actual-value\"}",
        "{\"se\\u0063ret\":\"null\\nactual-value\"}",
        "{\"token\":null,\"to\\u006ben\":\"external-live-value\"}"
      })
  void rejectsDecodedSensitiveFieldBeforeReturning(String json) {
    assertThatThrownBy(() -> guard.checkJson(json)).hasMessage("SENSITIVE_CONTENT").hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"api\\u005fkey\":\"<YOUR_API_KEY>\"}",
        "{\"to\\u006ben\":\"env:SYNTHETIC_REFERENCE\"}",
        "{\"pass\\u0077ord\":null,\"answer\":\"ordinary value\"}",
        "{\"se\\u0063ret\":\"***\",\"other\":\"ordinary value\"}",
        "{\"to\\u006ben\":\"${TOKEN}\"}",
        "{\"pass\\u0077ord\":\"\"}",
        "{\"label\":\"api_key\",\"answer\":\"ordinary value\"}",
        "[\"api_key\",\"ordinary value\"]"
      })
  void decodedFieldsKeepSafeReferencesAndUnrelatedValuesUsable(String json) {
    assertThatCode(() -> guard.checkJson(json)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "hello synthetic-secret-react-only-123456",
        "Authorization: Bearer abcd123456789",
        "-----BEGIN " + "PRIVATE KEY-----",
        "{\"api_key\":\"real-looking-sensitive-value\"}",
        "password: password123",
        "token: <actual-sensitive-token>",
        "-----BEGIN " + "OPENSSH PRIVATE KEY-----",
        "token=actual-token-value"
      })
  void rejectsSensitiveCarrierWithoutEcho(String text) {
    assertThatThrownBy(() -> guard.check(text)).hasMessage("SENSITIVE_CONTENT").hasNoCause();
    assertThat(guard.toString()).doesNotContain(text);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "请解释 API key 是什么",
        "credential-ref: env:DEEPSEEK_API_KEY",
        "api_key: <YOUR_API_KEY>",
        "token: ${TOKEN}",
        "password: <placeholder>",
        "{\"token\":null}",
        "token: ***",
        "普通中文"
      })
  void allowsExplanationAndReferences(String text) {
    assertThatCode(() -> guard.check(text)).doesNotThrowAnyException();
  }
}

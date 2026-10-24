package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.provider.*;
import org.junit.jupiter.api.Test;

class ProviderRuntimeRegressionTest {
  @Test
  void legacyProfileEntryStillUsesSameAuditedServiceWithoutProcessingLink() throws Exception {
    try (var f = new ReactRuntimeFixture();
        var r = f.openRuntime()) {
      var provider = r.context().getBean(ProfileProviderService.class);
      assertThat(provider.chatProfile("legacy-session", "p", ProfilePrompt.text("safe")).content())
          .isEqualTo("安全回复");
      assertThat(f.httpRequests).hasValue(1);
      try (var c = f.reopen();
          var s = c.createStatement()) {
        try (var rows = s.executeQuery("select count(*) from llm_request_links")) {
          assertThat(rows.getInt(1)).isZero();
        }
        try (var rows =
            s.executeQuery("select status,success,error_message,session_id from llm_calls")) {
          assertThat(rows.getString(1)).isEqualTo("SUCCEEDED");
          assertThat(rows.getBoolean(2)).isTrue();
          assertThat(rows.getString(3)).isNull();
          assertThat(rows.getString(4)).isEqualTo("legacy-session");
        }
        try (var rows = s.executeQuery("select version from aegisos_schema")) {
          assertThat(rows.getInt(1)).isEqualTo(3);
        }
      }
    }
  }
}

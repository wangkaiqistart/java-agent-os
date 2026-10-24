package com.aegisos.boot.react;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.boot.support.ReactRuntimeFixture;
import com.aegisos.core.agent.*;
import com.aegisos.core.profile.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 通过完整生产装配验证非法 Profile 在请求前不可用；不依赖正文读取的平台分支。 */
class ProfileRuntimeRegistrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"max_iterations: 101", "max_history_turns: 1001"})
  void invalidRuntimePolicyCannotCreateSessionOrPersistUser(String setting) throws Exception {
    try (var f = new ReactRuntimeFixture()) {
      Path agent = Files.createDirectories(f.root.resolve(".aegisos/agents/physical"));
      Files.writeString(
          agent.resolve("profile.yml"),
          "name: p\nprovider: {name: model-provider, model: test-model}\nsettings: {"
              + setting
              + "}\n");
      try (var r = f.openRuntime()) {
        var report = r.context().getBean(ProfileLoadReport.class);
        assertThat(report.profiles()).isEmpty();
        assertThat(report.sources().sources()).isEmpty();
        assertThat(report.diagnostics())
            .extracting(ProfileDiagnostic::code)
            .containsExactly(ProfileDiagnostic.Code.INVALID_FIELD);
        assertThatThrownBy(() -> r.agents().createSession("p"))
            .isInstanceOf(AgentException.class)
            .hasMessage("UNKNOWN_PROFILE");
        var result =
            r.agents().process(new AgentRequest(UUID.randomUUID().toString(), "p", "safe"));
        assertThat(result.errorCode()).isEqualTo(AgentErrorCode.UNKNOWN_PROFILE);
        assertThat(result.persistenceConfirmed()).isFalse();
        assertThat(f.httpRequests).hasValue(0);
        try (var connection = f.reopen();
            var statement = connection.createStatement()) {
          for (String table :
              java.util.List.of("sessions", "agent_processings", "session_messages")) {
            try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
              assertThat(rows.getInt(1)).as(table).isZero();
            }
          }
        }
      }
    }
  }
}

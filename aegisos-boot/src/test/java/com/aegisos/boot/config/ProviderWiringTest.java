package com.aegisos.boot.config;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderWiringTest {
  @TempDir Path dir;

  @Test
  void standaloneProviderUsesRealSqliteAndDefaultNoRetry() throws Exception {
    Path db = dir.resolve("audit.db");
    try (var stub =
            new BootProviderFixture(
                (e, n) ->
                    BootProviderFixture.reply(
                        e, n == 1 ? 200 : 401, n == 1 ? BootProviderFixture.text() : "{}"));
        var context = stub.productionContext(db)) {
      var service = context.getBean(ProviderService.class);
      var response = service.chat(ModelRequest.text("p", "m", "hello"));
      assertThat(response.usage().totalTokens()).isEqualTo(5);
      assertThatThrownBy(() -> service.chat(ModelRequest.text("p", "m", "x")))
          .isInstanceOfSatisfying(
              ProviderException.class,
              e ->
                  assertThat(e.failure().code())
                      .isEqualTo(ProviderErrorCode.AUTHENTICATION_FAILED));
      assertThat(stub.calls).hasValue(2);
      assertThat(BootProviderFixture.statuses(db)).containsExactly("SUCCEEDED", "FAILED");
      assertThat(List.of(context.getBeanDefinitionNames()))
          .noneMatch(
              n ->
                  n.contains("ReAct")
                      || n.contains("MemoryService")
                      || n.contains("ToolExecutor")
                      || n.contains("Controller"));
    }
  }

  @Test
  void startedIsVisibleAndAnotherWriterCommitsWhileModelIsBlocked() throws Exception {
    Path db = dir.resolve("concurrent.db");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var visible = new java.util.concurrent.atomic.AtomicBoolean();
    try (var stub =
            new BootProviderFixture(
                (e, n) -> {
                  try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
                      var q = c.createStatement();
                      var r = q.executeQuery("SELECT status FROM llm_calls")) {
                    visible.set(r.next() && r.getString(1).equals("STARTED"));
                  }
                  entered.countDown();
                  if (release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    BootProviderFixture.reply(e, 200, BootProviderFixture.text());
                });
        var context = stub.productionContext(db);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          executor.submit(
              () -> context.getBean(ProviderService.class).chat(ModelRequest.text("p", "m", "x")));
      try {
        assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(visible).isTrue();
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
            var q = c.createStatement()) {
          assertThat(
                  q.executeUpdate(
                      "UPDATE llm_calls SET provider_name=provider_name WHERE status='STARTED'"))
              .isEqualTo(1);
        }
      } finally {
        release.countDown();
      }
      assertThat(pending.get(3, java.util.concurrent.TimeUnit.SECONDS).content()).isEqualTo("ok");
      assertThat(stub.calls).hasValue(1);
      assertThat(BootProviderFixture.statuses(db)).containsExactly("SUCCEEDED");
    }
  }

  @Test
  void realDatabaseLockBeforeBeginPreventsExternalRequest() throws Exception {
    Path db = dir.resolve("before.db");
    try (var stub =
            new BootProviderFixture(
                (e, n) -> BootProviderFixture.reply(e, 200, BootProviderFixture.text()));
        var context = stub.productionContext(db);
        var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        var q = c.createStatement()) {
      q.execute("BEGIN EXCLUSIVE");
      try {
        assertThatThrownBy(
                () -> context.getBean(ProviderService.class).chat(ModelRequest.text("p", "m", "x")))
            .isInstanceOfSatisfying(
                ProviderException.class,
                e -> {
                  assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.AUDIT_UNAVAILABLE);
                  assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.NOT_SENT);
                });
        assertThat(stub.calls).hasValue(0);
      } finally {
        q.execute("ROLLBACK");
      }
      assertThat(BootProviderFixture.statuses(db)).isEmpty();
    }
  }

  @Test
  void realDatabaseLockAfterResponseLeavesStartedAndDoesNotResend() throws Exception {
    Path db = dir.resolve("after.db");
    var lock = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>();
    try (var stub =
            new BootProviderFixture(
                (e, n) -> {
                  var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
                  lock.set(c);
                  try (var q = c.createStatement()) {
                    q.execute("BEGIN EXCLUSIVE");
                  }
                  BootProviderFixture.reply(e, 200, BootProviderFixture.text());
                });
        var context = stub.productionContext(db)) {
      try {
        assertThatThrownBy(
                () -> context.getBean(ProviderService.class).chat(ModelRequest.text("p", "m", "x")))
            .isInstanceOfSatisfying(
                ProviderException.class,
                e -> {
                  assertThat(e.failure().code()).isEqualTo(ProviderErrorCode.AUDIT_UNAVAILABLE);
                  assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.UNKNOWN);
                  assertThat(e.failure().attemptCount()).isEqualTo(1);
                });
        assertThat(stub.calls).hasValue(1);
      } finally {
        var c = lock.get();
        if (c != null) {
          try (var q = c.createStatement()) {
            q.execute("ROLLBACK");
          } finally {
            c.close();
          }
        }
      }
      assertThat(BootProviderFixture.statuses(db)).containsExactly("STARTED");
    }
  }
}

package com.aegisos.tool;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.agent.*;
import com.aegisos.core.profile.*;
import com.aegisos.core.tool.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SandboxPolicyTest {
  static ToolExecutionContext context() {
    var p =
        new Profile(
            "p",
            null,
            null,
            new ProviderSelection("provider", "model", null),
            List.of("http_get"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    return new ToolExecutionContext(
        new ProcessingHandle(
            UUID.randomUUID(), UUID.randomUUID().toString(), "p", UUID.randomUUID(), 0),
        p,
        1,
        1);
  }

  static ValidatedArguments args(String url) {
    return new ValidatedArguments(Map.of("url", url), "{}", "a".repeat(64));
  }

  @Test
  void defaultDenyAndExactTargetBeforeDns() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var deny =
        new WhitelistSandbox(
            NetworkPolicy.defaults(List.of()),
            h -> {
              calls.incrementAndGet();
              return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            });
    assertThatThrownBy(() -> deny.authorize(context(), args("https://example.org/weather")))
        .hasMessage("SANDBOX_DENIED");
    assertThat(calls).hasValue(0);
  }

  @Test
  void privateMappedReservedAndTransitionAddressesAreNeverPublic() throws Exception {
    for (String ip :
        List.of(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "192.168.0.1",
            "100.64.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "224.0.0.1",
            "0.0.0.0",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2002:0808:0808::1",
            "64:ff9b::808:808",
            "::ffff:127.0.0.1"))
      assertThat(WhitelistSandbox.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
    assertThat(WhitelistSandbox.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
  }

  @Test
  void mixedDnsAndUriConfusionRejectBeforePermission() {
    var target = new NetworkPolicy.Target("https", "example.org", 443, "/weather", Set.of("q"));
    var mixed =
        new WhitelistSandbox(
            NetworkPolicy.defaults(List.of(target)),
            h ->
                new InetAddress[] {
                  InetAddress.getByName("8.8.8.8"), InetAddress.getLoopbackAddress()
                });
    assertThatThrownBy(() -> mixed.authorize(context(), args("https://example.org/weather?q=1")))
        .hasMessage("SANDBOX_DENIED");
    for (String url :
        List.of(
            "http://example.org/weather",
            "https://example.org.evil/weather",
            "https://u@example.org/weather",
            "https://example.org/weather#x",
            "https://example.org/weather?other=1",
            "https://example.org/%2e%2e/weather",
            "https://example.org:444/weather"))
      assertThatThrownBy(() -> mixed.authorize(context(), args(url))).hasMessage("SANDBOX_DENIED");
  }

  @Test
  void deprecatedRelayAndNewDocumentationRangesAreReserved() throws Exception {
    for (String address : List.of("192.88.99.1", "3fff::1", "3fff:f00::1"))
      assertThat(WhitelistSandbox.publicAddress(InetAddress.getByName(address)))
          .as(address)
          .isFalse();
  }
}

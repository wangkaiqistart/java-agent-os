package com.aegisos.provider.service;

import static org.assertj.core.api.Assertions.*;

import com.aegisos.core.provider.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ProviderErrorClassifierTest {
  @Test
  void onlyExplicitTemporaryNetworkTypesRetryWithoutRetainingCauses() {
    for (var cause :
        new Exception[] {
          new ConnectException(),
          new SocketException(),
          new EOFException(),
          new SocketTimeoutException()
        }) {
      var e = ProviderErrorClassifier.classify(new RuntimeException(cause), false);
      assertThat(e.failure().retryable()).isTrue();
      assertThat(e.failure().externalOutcome()).isEqualTo(ExternalOutcome.UNKNOWN);
      assertThat(e.getCause()).isNull();
    }
    for (var cause :
        new Exception[] {
          new UnknownHostException(),
          new SSLHandshakeException("unsafe"),
          new ProtocolException(),
          new IllegalArgumentException(),
          new IOException()
        }) {
      var e = ProviderErrorClassifier.classify(new RuntimeException(cause), false);
      assertThat(e.failure().retryable()).isFalse();
      assertThat(e.toString()).doesNotContain("unsafe");
    }
  }

  @Test
  void causeCyclesAreBounded() {
    var a = new RuntimeException();
    var b = new RuntimeException(a);
    a.initCause(b);
    assertThat(ProviderErrorClassifier.classify(a, false).failure().retryable()).isFalse();
  }
}

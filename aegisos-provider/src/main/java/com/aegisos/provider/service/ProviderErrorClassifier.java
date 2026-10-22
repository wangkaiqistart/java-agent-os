package com.aegisos.provider.service;

import com.aegisos.core.provider.*;
import com.openai.errors.*;
import java.io.*;
import java.net.*;
import javax.net.ssl.SSLException;

/** 只按类型/HTTP 状态分类，禁止复制原始 message、body、URL 或 cause。 */
public final class ProviderErrorClassifier {
  private ProviderErrorClassifier() {}

  public static ProviderException error(
      ProviderErrorCode code, boolean retryable, ExternalOutcome outcome) {
    return new ProviderException(
        new ProviderFailure(null, code, null, null, 1, retryable, outcome));
  }

  public static ProviderException classify(Throwable error, boolean responded) {
    int depth = 0;
    for (Throwable t = error; t != null && depth++ < 32; t = t.getCause()) {
      if (t instanceof ProviderException p) return p;
      if (t instanceof OpenAIServiceException http) {
        var code =
            switch (http.statusCode()) {
              case 401, 403 -> ProviderErrorCode.AUTHENTICATION_FAILED;
              case 404 -> ProviderErrorCode.MODEL_UNAVAILABLE;
              case 400, 422 -> ProviderErrorCode.INVALID_REQUEST;
              case 429 -> ProviderErrorCode.RATE_LIMITED;
              default -> ProviderErrorCode.PROVIDER_HTTP_ERROR;
            };
        if ((http.statusCode() == 400 || http.statusCode() == 404 || http.statusCode() == 422)
            && http.code()
                .filter(
                    value ->
                        value.equals("model_not_found")
                            || value.equals("model_not_available")
                            || value.equals("model_decommissioned"))
                .isPresent()) code = ProviderErrorCode.MODEL_UNAVAILABLE;
        return error(code, false, responded ? ExternalOutcome.RESPONDED : ExternalOutcome.UNKNOWN);
      }
      if (t instanceof SSLException
          || t instanceof UnknownHostException
          || t instanceof IllegalArgumentException)
        return error(
            ProviderErrorCode.INVALID_CONFIGURATION,
            false,
            responded ? ExternalOutcome.RESPONDED : ExternalOutcome.UNKNOWN);
      if (t instanceof SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException)
        return error(ProviderErrorCode.PROVIDER_TIMEOUT, true, ExternalOutcome.UNKNOWN);
      if (t instanceof InterruptedIOException && !Thread.currentThread().isInterrupted())
        return error(ProviderErrorCode.PROVIDER_TIMEOUT, true, ExternalOutcome.UNKNOWN);
      if (t instanceof ConnectException
          || t instanceof SocketException
          || t instanceof EOFException)
        return error(ProviderErrorCode.TEMPORARY_NETWORK_ERROR, true, ExternalOutcome.UNKNOWN);
      if (t == t.getCause()) break;
    }
    return error(
        ProviderErrorCode.PROVIDER_PROTOCOL_ERROR,
        false,
        responded ? ExternalOutcome.RESPONDED : ExternalOutcome.UNKNOWN);
  }
}

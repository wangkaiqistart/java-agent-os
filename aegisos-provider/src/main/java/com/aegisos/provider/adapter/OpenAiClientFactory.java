package com.aegisos.provider.adapter;

import com.aegisos.provider.config.ProviderDefinition;
import com.openai.client.*;
import com.openai.core.*;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.*;

public final class OpenAiClientFactory {
  /** 兼容入口默认不观测；只有显式冒烟装配提供不含数据的计数观察器。 */
  public ProviderAdapter create(ProviderDefinition definition, String credential) {
    return create(definition, credential, TransportSendObserver.NOOP);
  }

  public ProviderAdapter create(
      ProviderDefinition definition, String credential, TransportSendObserver observer) {
    var transport = new SingleRequestHttpClient(definition, observer);
    try {
      String root = definition.baseUrl().toString().replaceAll("/+$", "");
      var options =
          ClientOptions.builder()
              .httpClient(transport)
              .apiKey(credential)
              .baseUrl(root + "/v1")
              .maxRetries(0)
              .logLevel(LogLevel.OFF)
              .timeout(
                  Timeout.builder()
                      .connect(definition.connectTimeout())
                      .read(definition.readTimeout())
                      .write(definition.readTimeout())
                      .request(definition.readTimeout())
                      .build())
              .build();
      var model =
          OpenAiChatModel.builder()
              .openAiClient(new OpenAIClientImpl(options))
              .openAiClientAsync(new OpenAIClientAsyncImpl(options))
              .options(OpenAiChatOptions.builder().maxRetries(0).build())
              .observationRegistry(ObservationRegistry.NOOP)
              .build();
      return new OpenAiCompatibleAdapter(model, transport, definition.thinkingMode());
    } catch (RuntimeException e) {
      transport.close();
      throw com.aegisos.provider.service.ProviderErrorClassifier.error(
          com.aegisos.core.provider.ProviderErrorCode.INVALID_CONFIGURATION,
          false,
          com.aegisos.core.provider.ExternalOutcome.NOT_SENT);
    }
  }
}

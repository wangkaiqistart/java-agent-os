package com.aegisos.provider.adapter;

import com.aegisos.core.provider.*;
import com.aegisos.provider.service.ProviderErrorClassifier;
import org.springframework.ai.openai.OpenAiChatModel;

public final class OpenAiCompatibleAdapter implements ProviderAdapter {
  private final OpenAiChatModel model;
  private final SingleRequestHttpClient transport;

  OpenAiCompatibleAdapter(OpenAiChatModel model, SingleRequestHttpClient transport) {
    this.model = model;
    this.transport = transport;
  }

  public ModelResponse chat(ModelRequest request) {
    var prompt = OpenAiMessageMapper.prompt(request);
    transport.begin();
    try {
      return OpenAiMessageMapper.response(request, model.call(prompt), transport.usage());
    } catch (RuntimeException e) {
      throw ProviderErrorClassifier.classify(e, transport.responded());
    } finally {
      transport.clear();
    }
  }

  @Override
  public void close() {
    transport.close();
  }
}

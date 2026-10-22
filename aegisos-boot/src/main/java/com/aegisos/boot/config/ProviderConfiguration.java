package com.aegisos.boot.config;

import com.aegisos.core.audit.*;
import com.aegisos.core.provider.*;
import com.aegisos.provider.adapter.OpenAiClientFactory;
import com.aegisos.provider.config.*;
import com.aegisos.provider.service.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
@Import(ProfileConfiguration.class)
public class ProviderConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public EnvironmentCredentialResolver credentialResolver() {
    return new EnvironmentCredentialResolver(System::getenv);
  }

  @Bean(destroyMethod = "close")
  public ProviderRegistry providerRegistry(
      ProviderProperties properties,
      EnvironmentCredentialResolver credentials,
      Environment environment,
      org.springframework.beans.factory.ObjectProvider<
              com.aegisos.provider.adapter.TransportSendObserver>
          observers) {
    var definitions = properties.snapshot(environment.acceptsProfiles(Profiles.of("test")));
    var registrations = new ArrayList<ProviderRegistration>();
    try {
      for (var d : definitions) {
        ProviderErrorCode reason = null;
        if (d.baseUrl() == null
            || d.maxRetries() < 0
            || d.connectTimeout().isZero()
            || d.readTimeout().isZero()
            || d.models().isEmpty()
            || d.credentialRef() == null) reason = ProviderErrorCode.INVALID_CONFIGURATION;
        else if (!"openai-compatible".equals(d.adapter()))
          reason = ProviderErrorCode.PROVIDER_UNAVAILABLE;
        String credential = reason == null ? credentials.resolve(d.credentialRef()) : null;
        if (reason == null && credential == null) reason = ProviderErrorCode.PROVIDER_UNAVAILABLE;
        if (reason == null && (credential.equals(d.name()) || d.models().contains(credential)))
          reason = ProviderErrorCode.INVALID_CONFIGURATION;
        if (reason != null) {
          registrations.add(new ProviderRegistration(d, null, reason));
          continue;
        }
        try {
          registrations.add(
              new ProviderRegistration(
                  d,
                  new OpenAiClientFactory()
                      .create(
                          d,
                          credential,
                          observers.getIfAvailable(
                              () -> com.aegisos.provider.adapter.TransportSendObserver.NOOP)),
                  null));
        } catch (RuntimeException e) {
          registrations.add(
              new ProviderRegistration(d, null, ProviderErrorCode.INVALID_CONFIGURATION));
        }
      }
      return new ProviderRegistry(registrations);
    } catch (RuntimeException e) {
      registrations.stream()
          .filter(ProviderRegistration::available)
          .forEach(r -> r.adapter().close());
      throw new IllegalArgumentException("Invalid provider configuration");
    }
  }

  @Bean
  public DefaultProviderService providerService(
      ProviderRegistry registry,
      LlmCallAuditStore audit,
      com.aegisos.core.profile.ProfileRegistry profiles) {
    return new DefaultProviderService(registry, audit, RetryWaiter.fixed(), profiles);
  }
}

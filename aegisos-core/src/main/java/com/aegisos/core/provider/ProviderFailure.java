package com.aegisos.core.provider;

import java.util.UUID;

public record ProviderFailure(
    UUID requestId,
    ProviderErrorCode code,
    String providerName,
    String modelName,
    long attemptCount,
    boolean retryable,
    ExternalOutcome externalOutcome) {}

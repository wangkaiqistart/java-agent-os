package com.aegisos.core.audit;

import com.aegisos.core.provider.*;
import java.time.Instant;

public record AuditCompletion(
    AuditStatus status,
    Instant completedAt,
    long durationMs,
    TokenUsage usage,
    ProviderErrorCode errorCode,
    boolean retryable) {}

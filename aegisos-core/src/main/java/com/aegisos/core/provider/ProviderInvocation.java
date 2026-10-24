package com.aegisos.core.provider;

import java.util.UUID;

/** 显式审计关联，不承载正文；合法性由存储事务验证。 */
public record ProviderInvocation(UUID processId, int iterationNo) {}

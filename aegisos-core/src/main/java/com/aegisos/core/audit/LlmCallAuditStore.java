package com.aegisos.core.audit;

/** 每个方法返回意味着独立短事务已提交；不持有跨外呼事务。 */
public interface LlmCallAuditStore {
  AuditHandle begin(AuditStart start);

  void complete(AuditHandle handle, AuditCompletion completion);

  void reject(AuditRejection rejection);
}

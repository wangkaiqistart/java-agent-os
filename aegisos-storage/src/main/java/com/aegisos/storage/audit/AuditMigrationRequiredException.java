package com.aegisos.storage.audit;

/** 固定安全诊断；启动器仅拒绝旧库，不执行迁移或泄漏数据库路径。 */
public final class AuditMigrationRequiredException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public AuditMigrationRequiredException() {
    super("MIGRATION_REQUIRED", null, false, false);
  }
}

package com.arte.base.model.artifact;

/**
 * 产物存储生命周期；可用不等于已插入文章或已获访问权限。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum ArtifactStatus {
    RECEIVING,
    QUARANTINED,
    VALIDATING,
    AVAILABLE,
    REFERENCED,
    EXPIRED,
    DELETED
}

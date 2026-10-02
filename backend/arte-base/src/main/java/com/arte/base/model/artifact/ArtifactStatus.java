package com.arte.base.model.artifact;

/**
 * 产物存储生命周期；可用不等于已插入文章或已获访问权限。
 *
 * REFERENCED 的引用关系及解除由领域管理，本批不提供自动解除／删除仍被引用产物。
 */
public enum ArtifactStatus {
    RECEIVING,
    QUARANTINED,
    VALIDATING,
    AVAILABLE,
    REFERENCED,
    EXPIRED,
    DELETED;

    public boolean canTransitionTo(ArtifactStatus next) {
        if (next == null || next == this || this == DELETED || this == REFERENCED) return false;
        if (next == DELETED) return true;
        if (next == EXPIRED) return this != EXPIRED;
        return switch (this) {
            case RECEIVING -> next == QUARANTINED;
            case QUARANTINED -> next == VALIDATING;
            case VALIDATING -> next == AVAILABLE || next == QUARANTINED;
            case AVAILABLE -> next == REFERENCED;
            default -> false;
        };
    }
}

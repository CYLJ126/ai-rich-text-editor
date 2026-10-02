package com.arte.base.model.security;

/**
 * 策略判定状态；提供者不可用、未接入或无法核对时返回 INDETERMINATE，不能作为允许。
 */
public enum PolicyOutcome {
    ALLOW,
    DENY,
    INDETERMINATE
}

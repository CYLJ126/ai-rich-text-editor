package com.arte.base.model.observability;

import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 只有提供者完成耐久追加后才能返回；重复事件不得改变原审计事实。
 */
public record AuditReceipt(String eventId, String contentDigest, Instant recordedAt) {
    public AuditReceipt {
        eventId = ContractChecks.identifier(eventId, "eventId");
        contentDigest = ContractChecks.identifier(contentDigest, "contentDigest");
        recordedAt = ContractChecks.required(recordedAt, "recordedAt");
    }
}

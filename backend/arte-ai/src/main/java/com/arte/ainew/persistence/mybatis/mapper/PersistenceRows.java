package com.arte.ainew.persistence.mybatis.mapper;

/**
 * 数据库投影；不承载业务行为，也不作为外部 API 对象。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public final class PersistenceRows {
    private PersistenceRows() {
    }

    public record AcceptanceRow(String requestDigest, String invocationKey) {
    }

    public record ConversationGateRow(long versionNo, String activeInvocation) {
    }

    public record AttemptCountersRow(int nextAttempt, long nextFence) {
    }

    public record OperationRow(String digest, long firstSequence, int eventCount, String resultSnapshot) {
    }

    public record PendingMessageRow(
            String messageKey,
            String invocationId,
            long sequenceNo,
            long token,
            String ownerTenant,
            String ownerWorkspace,
            String ownerSubject) {
    }

    public record OutboxRow(
            String workerId,
            long token,
            String invocationKey,
            String ownerTenant,
            String ownerWorkspace,
            String ownerSubject,
            String kind,
            long sequenceNo,
            int delivered,
            long leaseUntil) {
    }

    public record SettlementRow(String digest, String resultSnapshot) {
    }
}

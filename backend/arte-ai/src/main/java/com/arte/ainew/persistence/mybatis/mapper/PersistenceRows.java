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

    public record ConversationCreationRow(String requestDigest, String conversationKey) { }

    /**
     * workspaceSnapshot 对应 workspace_snapshot 列的 JSON 字节，内容为上下文或专有结果快照。
     */
    public record ResultBatchRow(String idKey, String ownerKey, String invocationKey, String attemptKey,
                                 String resultType,
                                 int schemaVersion, int partial, String payloadDigest, String workspaceSnapshot) {
        public PayloadRow payload() {
            return new PayloadRow(ownerKey, invocationKey, attemptKey, resultType,
                    schemaVersion, partial, payloadDigest, workspaceSnapshot);
        }
    }

    public record PayloadRow(String ownerKey, String invocationKey, String attemptKey, String resultType,
                             int schemaVersion, int partial, String payloadDigest, String workspaceSnapshot) { }

    public record ConversationGateRow(long versionNo, String activeInvocation, String activeKind) {
        public ConversationGateRow(long versionNo, String activeInvocation) {
            this(versionNo, activeInvocation, "INVOCATION");
        }
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
            long leaseUntil,
            String workClass,
            int finished) {
    }

    public record SettlementRow(String digest, String resultSnapshot) {
    }
}

package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.persistence.mybatis.mapper.ToolAdmissionMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolApprovalMapper;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;

import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 审批快照及索引的完整性检查，由受理、决定和通用取消共用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
final class ToolApprovalRecords {

    private ToolApprovalRecords() {
    }

    static ToolApprovalMapper.Row row(ToolApproval value, ExecutionRecordCodec codec) {
        String json = CanonicalJson.normalize(codec.encode(value));
        return new ToolApprovalMapper.Row(CanonicalJson.key(value.invocationId()),
                CanonicalJson.key(value.owner().tenantId(), value.owner().workspaceId(), value.owner().subjectId()),
                CanonicalJson.key(value.approvalId()), value.state().name(), value.version(), value.plan().expiresAt().toEpochMilli(),
                CanonicalJson.sha256(json), json);
    }

    static ToolApproval read(ToolApprovalMapper.Row row, ExecutionRecordCodec codec) {
        if (row == null) return null;
        if (!CanonicalJson.sha256(row.snapshot()).equals(row.payloadDigest()))
            throw new IllegalStateException("Approval snapshot bytes are corrupt");
        var value = codec.decode(row.snapshot(), ToolApproval.class);
        // Set 的内部编码顺序可能随 JVM 改变；验证原始字节摘要和语义投影，不要求反序列化后字节完全相同。
        if (!key(value.invocationId()).equals(row.invocationKey())
                || !key(value.owner().tenantId(), value.owner().workspaceId(), value.owner().subjectId()).equals(row.ownerKey())
                || !key(value.approvalId()).equals(row.approvalKey()) || !value.state().name().equals(row.state())
                || value.version() != row.version() || value.plan().expiresAt().toEpochMilli() != row.expiresAt())
            throw new IllegalStateException("Approval snapshot projections mismatch");
        return value;
    }

    static ToolExecutionSnapshot snapshot(ToolApproval approval, Invocation invocation,
                                          ToolAdmissionMapper snapshots, ExecutionRecordCodec codec) {
        var row = snapshots.toolSnapshot(key(approval.invocationId()), key(approval.owner().tenantId(), approval.owner().workspaceId(), approval.owner().subjectId()));
        if (row == null || !CanonicalJson.sha256(row.snapshot()).equals(row.payloadDigest()))
            throw new IllegalStateException("Approval execution snapshot missing or corrupt");
        var snapshot = codec.decode(row.snapshot(), ToolExecutionSnapshot.class);
        if (!snapshot.request().equals(invocation.request()) || !snapshot.requestDigest().equals(invocation.requestDigest())
                || !row.requestDigest().equals(snapshot.requestDigest()) || !row.submissionDigest().equals(snapshot.submissionDigest())
                || !row.bindingKey().equals(key(snapshot.input().bindingId()))
                || !row.idempotencyKey().equals(key(snapshot.request().context().idempotencyKey()))
                || !approval.subjectDigest().equals(ToolApproval.digest(snapshot, approval.plan())))
            throw new IllegalStateException("Approval subject integrity mismatch");
        approval.plan().requireMatches(snapshot);
        return snapshot;
    }
}

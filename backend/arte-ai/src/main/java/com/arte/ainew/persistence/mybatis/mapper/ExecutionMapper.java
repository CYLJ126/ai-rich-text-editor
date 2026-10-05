package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.AcceptanceRow;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.AttemptCountersRow;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.OperationRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface ExecutionMapper {
    String attemptPurpose(@Param("idKey") String idKey);

    int saveInvocation(@Param("snapshot") String snapshot, @Param("idKey") String idKey);

    int saveAttempt(@Param("snapshot") String snapshot, @Param("idKey") String idKey);

    List<AcceptanceRow> acceptance(@Param("scopeKey") String scopeKey, @Param("idempotencyKey") String idempotencyKey);

    int insertInvocation(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey, @Param("snapshot") String snapshot);

    int insertAcceptance(
            @Param("scopeKey") String scopeKey,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("invocationKey") String invocationKey);

    AttemptCountersRow attemptCounters(@Param("idKey") String idKey);

    int insertAttempt(
            @Param("idKey") String idKey,
            @Param("invocationKey") String invocationKey,
            @Param("attemptNumber") int attemptNumber,
            @Param("purpose") String purpose,
            @Param("snapshot") String snapshot);

    int advanceAttemptCounters(
            @Param("nextAttempt") int nextAttempt,
            @Param("nextFence") long nextFence,
            @Param("idKey") String idKey);

    long nextFence(@Param("idKey") String idKey);

    int savePurpose(@Param("purpose") String purpose, @Param("idKey") String idKey);

    int saveNextFence(@Param("nextFence") long nextFence, @Param("idKey") String idKey);

    List<OperationRow> operation(@Param("invocationKey") String invocationKey, @Param("operationKey") String operationKey);

    int insertCompletion(
            @Param("invocationKey") String invocationKey,
            @Param("operationKey") String operationKey,
            @Param("digest") String digest,
            @Param("firstSequence") long firstSequence,
            @Param("eventCount") int eventCount,
            @Param("resultSnapshot") String resultSnapshot);

    int insertVerifiedCompletion(
            @Param("invocationKey") String invocationKey,
            @Param("operationKey") String operationKey,
            @Param("digest") String digest,
            @Param("firstSequence") long firstSequence,
            @Param("eventCount") int eventCount,
            @Param("resultSnapshot") String resultSnapshot,
            @Param("evidenceRef") String evidenceRef);

    int insertAppend(
            @Param("invocationKey") String invocationKey,
            @Param("operationKey") String operationKey,
            @Param("digest") String digest,
            @Param("firstSequence") long firstSequence,
            @Param("eventCount") int eventCount);

    String invocationSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);

    String attemptSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);
}

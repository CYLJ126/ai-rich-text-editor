package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SearchSyncMapper {
    long databaseTimeMillis();

    record Generation(String generation, String rulesVersion, String worker, long fence, long leaseUntil,
                      Long lastSuccessAt, long validationEpoch) {
    }

    record Checkpoint(String lane, String cursorKey, long rounds, long nextAt) {
    }

    record Work(String taskKey, String kind, String sourceKey, String tenantId, String workspaceId, String subjectId,
                String conversationId, boolean forceWrite, int failures) {
    }

    record Entry(String entryKey, String kind, String sourceKey, String tenantId, String workspaceId, String subjectId,
                 String conversationId, String identityJson, String fingerprint, long revision, long acknowledged,
                 String desired) {
    }

    int register(@Param("g") String generation, @Param("rules") String rules);

    Generation generation(@Param("g") String generation, @Param("lock") boolean lock);

    int lease(@Param("g") String generation, @Param("worker") String worker, @Param("fence") long fence, @Param("until") long until);

    int success(@Param("g") String generation, @Param("now") long now);

    int ensureCheckpoint(@Param("g") String generation, @Param("lane") String lane);

    Checkpoint checkpoint(@Param("g") String generation, @Param("lane") String lane);

    List<Checkpoint> checkpoints(@Param("g") String generation);

    int checkpointSave(@Param("g") String generation, @Param("lane") String lane, @Param("cursor") String cursor,
                       @Param("rounds") long rounds, @Param("next") long next);

    int enqueue(@Param("g") String generation, @Param("task") String task, @Param("kind") String kind,
                @Param("key") String key, @Param("force") boolean force);

    int requeue(@Param("g") String generation, @Param("task") String task, @Param("force") boolean force);

    List<Work> known(@Param("g") String generation, @Param("after") String after, @Param("size") int size);

    List<Work> pending(@Param("g") String generation, @Param("now") long now, @Param("size") int size);

    Long nextWorkAt(@Param("g") String generation);

    int complete(@Param("g") String generation, @Param("task") String task, @Param("tenant") String tenant,
                 @Param("space") String space, @Param("subject") String subject, @Param("conv") String conversation, @Param("owner") String owner);

    int continueWork(@Param("g") String generation, @Param("task") String task, @Param("next") long next);

    int fail(@Param("g") String generation, @Param("task") String task, @Param("code") String code, @Param("next") long next);

    List<Entry> entries(@Param("g") String generation, @Param("task") String task);

    Entry entry(@Param("g") String generation, @Param("id") String id);

    int insertEntry(@Param("g") String generation, @Param("r") Entry row, @Param("task") String task, @Param("owner") String owner);

    int updateEntry(@Param("g") String generation, @Param("r") Entry row);

    int acknowledge(@Param("g") String generation, @Param("id") String id, @Param("rev") long revision);

    long pendingCount(@Param("g") String generation, @Param("owner") String owner, @Param("errors") boolean errors);

    long missingCount(@Param("g") String generation, @Param("owner") String owner);

    long maxFailures(@Param("g") String generation, @Param("owner") String owner);

    int bindScope(@Param("g") String generation, @Param("task") String task, @Param("tenant") String tenant, @Param("space") String space, @Param("subject") String subject, @Param("conv") String conversation, @Param("owner") String owner, @Param("state") String state);

    int validation(@Param("g") String generation, @Param("epoch") long epoch);

    long entryCount(@Param("g") String generation, @Param("owner") String owner);
}

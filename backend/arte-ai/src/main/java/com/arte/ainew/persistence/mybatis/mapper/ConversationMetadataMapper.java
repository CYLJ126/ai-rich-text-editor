package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 轻量列表投影；所有写入由调用方事务管理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
public interface ConversationMetadataMapper {

    record Row(String idKey, String ownerKey, String conversationId, String title, long metadataVersion,
               boolean pinned, boolean titleManuallyEdited, boolean autoTitleResolved, String previewText,
               Long userMessageCount, Long lastMessageMicros, long sortMicros, long createdMicros,
               boolean statisticsReady) {
    }

    record CommandRow(String requestDigest, String workspaceSnapshot) {
    }

    record LegacyRow(String idKey, String workspaceSnapshot) {
    }

    record Job(String idKey, long turnSequence, int invocationIndex, long eventSequence, boolean incomplete) {
    }

    Row findCurrent(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey);

    Long factCurrent(@Param("conversationKey") String conversationKey, @Param("factKey") String factKey);

    int enqueue(@Param("idKey") String idKey);

    List<Job> jobs(@Param("limit") int limit);

    Job job(@Param("idKey") String idKey);

    int saveJob(@Param("j") Job job);

    int finishJob(@Param("idKey") String idKey);

    long userFacts(@Param("idKey") String idKey);

    Long lastFact(@Param("idKey") String idKey);

    int savePreviewSequence(@Param("idKey") String idKey, @Param("sequence") long sequence);

    int backfillPreview(@Param("idKey") String idKey, @Param("sequence") long sequence, @Param("preview") String preview);

    Row find(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey);

    int insert(@Param("r") Row row, @Param("active") boolean active);

    int saveMetadata(@Param("r") Row row);

    int saveStatistics(@Param("r") Row row);

    long count(@Param("ownerKey") String ownerKey, @Param("pinned") boolean pinned);

    List<Row> list(@Param("ownerKey") String ownerKey, @Param("pinned") boolean pinned,
                   @Param("sortMicros") Long sortMicros, @Param("conversationId") String conversationId,
                   @Param("inclusive") boolean inclusive, @Param("limit") int limit);

    CommandRow command(@Param("ownerKey") String ownerKey, @Param("commandKey") String commandKey);

    int insertCommand(@Param("ownerKey") String ownerKey, @Param("commandKey") String commandKey,
                      @Param("digest") String digest, @Param("workspaceSnapshot") String workspaceSnapshot);

    int insertFact(@Param("conversationKey") String conversationKey, @Param("factKey") String factKey,
                   @Param("role") String role, @Param("generatedMicros") long generatedMicros);

    Long fact(@Param("conversationKey") String conversationKey, @Param("factKey") String factKey);

    List<LegacyRow> missing(@Param("afterKey") String afterKey, @Param("limit") int limit);
}

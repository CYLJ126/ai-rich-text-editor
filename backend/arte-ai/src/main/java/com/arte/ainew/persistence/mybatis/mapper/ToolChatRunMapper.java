package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Run、聊天归属及独立事件 SQL；会话／Turn 复用 AdmissionMapper，不写通用 Invocation 或账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public interface ToolChatRunMapper {

    record Row(String runKey, String ownerKey, String idempotencyKey, String submissionDigest, String inputDigest,
               String inputSnapshot, String stateDigest, String stateSnapshot, long version, long deadline) {
    }

    record Work(String runKey, long availableAt, boolean enabled, String workerId, long leaseUntil, long fence,
                long signalVersion) {
    }

    record Step(String childKey, String runKey, String payloadDigest, String snapshot) {
    }

    record Receipt(String requestDigest, String payloadDigest, String snapshot) {
    }

    record Chat(String runKey, String conversationId, String turnId, long conversationVersion,
                String requestDigest, String finalInvocationId) {
    }

    List<Row> findMany(@Param("runs") List<String> runKeys);

    List<Chat> chats(@Param("runs") List<String> runKeys);

    Chat chat(@Param("run") String runKey);

    int insertChat(@Param("c") Chat chat);

    int finishChat(@Param("run") String runKey, @Param("invocation") String invocationId);

    int insertEvent(@Param("run") String runKey, @Param("sequence") long sequence, @Param("snapshot") String snapshot);

    List<String> events(@Param("run") String runKey, @Param("after") long after, @Param("limit") int limit);

    Row find(@Param("run") String runKey, @Param("lock") boolean lock);

    Row accepted(@Param("owner") String owner, @Param("idempotency") String idempotency);

    int insert(@Param("r") Row row);

    int update(@Param("run") String runKey, @Param("version") long version, @Param("digest") String digest, @Param("snapshot") String snapshot);

    Work work(@Param("run") String runKey);

    int insertWork(@Param("w") Work work);

    int updateWork(@Param("w") Work work);

    List<String> due(@Param("now") long now, @Param("limit") int limit);

    List<Step> steps(@Param("run") String runKey);

    int insertStep(@Param("s") Step step);

    Receipt receipt(@Param("run") String runKey, @Param("command") String commandKey);

    int insertReceipt(@Param("run") String runKey, @Param("command") String commandKey, @Param("r") Receipt receipt);
}

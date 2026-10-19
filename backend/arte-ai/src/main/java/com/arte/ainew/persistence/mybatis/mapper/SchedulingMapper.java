package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * S2-06 同库调度索引、容量许可和公平游标；只注册到新 AI 的独立 MyBatis 工厂。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
public interface SchedulingMapper {

    record Queued(String messageKey, String invocationKey, String invocationId, String ownerTenant,
                  String ownerWorkspace,
                  String ownerSubject, String workClass, String providerKey, String spaceKey, String subjectKey,
                  long queuedAt, long queueDeadline, long token, int queuePending, int queueReserved, int finished) {
    }

    record Permit(String messageKey, long token, String workClass, String spaceKey, String providerKey,
                  long leaseUntil) {
    }

    record Pool(String policyDigest, String manifestDigest) {
    }

    /**
     * 同一事务内锁定的队列预留事实；key 对应全局、分类、空间或主体维度。
     */
    record QueueCounter(String key, long reservations) {
    }

    List<QueueCounter> queueCounts(@Param("keys") List<String> keys);

    int createQueueCounts(@Param("keys") List<String> keys);

    int incrementQueueCounts(@Param("keys") List<String> keys);

    int decrementQueueCounts(@Param("keys") List<String> keys);

    List<Queued> ready(@Param("type") String type, @Param("now") long now, @Param("limit") int limit);

    List<Queued> expiredQueue(@Param("now") long now, @Param("limit") int limit);

    Queued metadata(@Param("key") String key);

    int classify(@Param("key") String key, @Param("type") String type, @Param("provider") String provider,
                 @Param("space") String space, @Param("subject") String subject, @Param("queued") long queued, @Param("deadline") long deadline);

    int started(@Param("invocation") String invocation, @Param("release") boolean release);

    int finish(@Param("invocation") String invocation);

    int requeue(@Param("invocation") String invocation);

    List<Permit> permits(@Param("now") long now);

    int permit(@Param("key") String key, @Param("token") long token, @Param("type") String type,
               @Param("space") String space, @Param("provider") String provider, @Param("until") long until);

    int renew(@Param("key") String key, @Param("token") long token, @Param("until") long until);

    int release(@Param("key") String key, @Param("token") long token);

    Pool pool();

    int createPool(@Param("policy") String policy, @Param("manifest") String manifest);

    String cursor(@Param("type") String type);

    int createCursor(@Param("type") String type, @Param("space") String space);

    int saveCursor(@Param("type") String type, @Param("space") String space);
}

package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.OutboxRow;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.PendingMessageRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface OutboxMapper {
    int insertMessage(
            @Param("messageKey") String messageKey,
            @Param("invocationKey") String invocationKey,
            @Param("invocationId") String invocationId,
            @Param("ownerTenant") String ownerTenant,
            @Param("ownerWorkspace") String ownerWorkspace,
            @Param("ownerSubject") String ownerSubject,
            @Param("kind") String kind,
            @Param("sequenceNo") long sequenceNo);

    List<PendingMessageRow> pendingMessages(
            @Param("kind") String kind,
            @Param("currentTime") long currentTime,
            @Param("limit") int limit);

    int claimMessage(
            @Param("workerId") String workerId,
            @Param("token") long token,
            @Param("leaseUntil") long leaseUntil,
            @Param("messageKey") String messageKey);

    List<OutboxRow> lockMessage(@Param("messageKey") String messageKey);

    int deliverMessage(@Param("messageKey") String messageKey);

    long countUndelivered(
            @Param("invocationKey") String invocationKey,
            @Param("kind") String kind,
            @Param("throughSequence") long throughSequence);
}

package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.ConversationGateRow;
import org.apache.ibatis.annotations.Param;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface AdmissionMapper {
    int insertConversation(
            @Param("idKey") String idKey,
            @Param("ownerKey") String ownerKey,
            @Param("versionNo") long versionNo,
            @Param("snapshot") String snapshot);

    ConversationGateRow conversationGate(@Param("idKey") String idKey);

    long maximumTurnSequence(@Param("conversationKey") String conversationKey);

    long countTurnSequence(@Param("conversationKey") String conversationKey, @Param("sequenceNo") long sequenceNo);

    int insertTurn(
            @Param("idKey") String idKey,
            @Param("conversationKey") String conversationKey,
            @Param("sequenceNo") long sequenceNo,
            @Param("snapshot") String snapshot);

    int saveTurn(@Param("snapshot") String snapshot, @Param("idKey") String idKey);

    int advanceConversation(
            @Param("versionNo") long versionNo,
            @Param("activeInvocation") String activeInvocation,
            @Param("snapshot") String snapshot,
            @Param("idKey") String idKey);

    int releaseConversation(@Param("idKey") String idKey, @Param("activeInvocation") String activeInvocation);

    String conversationSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);

    String turnSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);
}

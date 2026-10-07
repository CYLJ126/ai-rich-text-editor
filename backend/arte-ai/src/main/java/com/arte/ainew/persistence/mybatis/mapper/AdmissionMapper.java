package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.ConversationCreationRow;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.ConversationGateRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface AdmissionMapper {
    ConversationCreationRow conversationCreation(@Param("ownerKey") String ownerKey, @Param("commandKey") String commandKey);

    int insertConversationCreation(@Param("ownerKey") String ownerKey, @Param("commandKey") String commandKey,
                                   @Param("requestDigest") String requestDigest, @Param("conversationKey") String conversationKey);

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

    long countConversations(@Param("ownerKey") String ownerKey);

    List<String> conversationSnapshots(@Param("ownerKey") String ownerKey, @Param("offset") long offset,
                                       @Param("limit") long limit);

    long countTurns(@Param("conversationKey") String conversationKey);

    List<String> turnSnapshots(@Param("conversationKey") String conversationKey, @Param("offset") long offset,
                               @Param("limit") long limit);
}

package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolCallPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_call", queryFields = {})
public interface ToolCallMapper extends BaseMapper<ToolCallPo> {
    Optional<ToolCallPo> selectByCallId(@Param("callId") String callId);

    Optional<ToolCallPo> selectByIdempotencyKey(@Param("ownerId") String ownerId,
                                                @Param("idempotencyKey") String idempotencyKey);

    int transitionStatus(@Param("callId") String callId,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("completedAt") LocalDateTime completedAt);

    int complete(@Param("callId") String callId,
                 @Param("status") String status,
                 @Param("completedAt") LocalDateTime completedAt,
                 @Param("latencyMs") Long latencyMs,
                 @Param("errorCode") String errorCode,
                 @Param("errorCategory") String errorCategory,
                 @Param("errorMessage") String errorMessage,
                 @Param("inputTokens") Integer inputTokens,
                 @Param("outputTokens") Integer outputTokens,
                 @Param("totalTokens") Integer totalTokens,
                 @Param("expectedVersion") Long expectedVersion);

    Optional<ToolCallPo> selectOwned(@Param("callId") String callId,
                                     @Param("ownerId") String ownerId);

    java.util.List<ToolCallPo> selectDetails(@Param("ownerId") String ownerId,
                                             @Param("toolId") String toolId,
                                             @Param("status") String status,
                                             @Param("limit") int limit);
}

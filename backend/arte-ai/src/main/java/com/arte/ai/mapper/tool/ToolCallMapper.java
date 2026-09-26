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
}

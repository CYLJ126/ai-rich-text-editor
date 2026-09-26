package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_approval", queryFields = {})
public interface ToolApprovalMapper extends BaseMapper<ToolApprovalPo> {
    Optional<ToolApprovalPo> selectByRequestId(@Param("requestId") String requestId);

    int decideIfPending(@Param("requestId") String requestId, @Param("status") String status,
                        @Param("approverId") String approverId, @Param("reason") String reason,
                        @Param("decidedAt") LocalDateTime decidedAt);

    int expirePending(@Param("now") LocalDateTime now);
}

package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolApprovalPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_approval", queryFields = {})
public interface ToolApprovalMapper extends BaseMapper<ToolApprovalPo> {
    Optional<ToolApprovalPo> selectByRequestId(@Param("requestId") String requestId);

    int decideIfPending(@Param("requestId") String requestId, @Param("status") String status,
                        @Param("approverId") String approverId, @Param("reason") String reason,
                        @Param("decidedAt") LocalDateTime decidedAt);

    int expirePending(@Param("now") LocalDateTime now);

    int attachTask(@Param("requestId") String requestId, @Param("taskId") String taskId);

    List<ToolApprovalPo> selectPendingByOwner(@Param("ownerId") String ownerId);

    List<ToolApprovalPo> selectByOwner(@Param("ownerId") String ownerId,
                                       @Param("status") String status,
                                       @Param("limit") int limit);

    /**
     * 查找已决策但任务仍停留在审批等待态的记录，用于节点故障后的分布式对账。
     */
    List<ToolApprovalPo> selectActionable(@Param("limit") int limit);
}

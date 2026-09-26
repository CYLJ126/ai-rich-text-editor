package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolTaskPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_task", queryFields = {})
public interface ToolTaskMapper extends BaseMapper<ToolTaskPo> {
    Optional<ToolTaskPo> selectByTaskId(@Param("taskId") String taskId);

    int tryClaim(@Param("taskId") String taskId, @Param("workerId") String workerId,
                 @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int renewLease(@Param("taskId") String taskId, @Param("workerId") String workerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    List<ToolTaskPo> selectRecoverable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int updateProgress(@Param("taskId") String taskId, @Param("workerId") String workerId,
                       @Param("progress") BigDecimal progress, @Param("message") String message,
                       @Param("expectedVersion") Long expectedVersion);

    int transitionStatus(@Param("taskId") String taskId, @Param("expectedStatus") String expectedStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("expectedVersion") Long expectedVersion);
}

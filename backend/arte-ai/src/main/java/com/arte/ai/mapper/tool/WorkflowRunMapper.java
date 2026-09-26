package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowRunPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow_run", queryFields = {})
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunPo> {
    Optional<WorkflowRunPo> selectByRunId(@Param("runId") String runId);

    int transitionStatus(@Param("runId") String runId, @Param("expectedStatus") String expectedStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("completedAt") LocalDateTime completedAt);
}

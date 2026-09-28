package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowRunPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow_run", queryFields = {})
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunPo> {
    Optional<WorkflowRunPo> selectByRunId(@Param("runId") String runId);

    Optional<WorkflowRunPo> selectOwned(@Param("runId") String runId,
                                        @Param("ownerId") String ownerId);

    List<WorkflowRunPo> selectOwnedPage(@Param("ownerId") String ownerId,
                                        @Param("workflowId") String workflowId,
                                        @Param("status") String status,
                                        @Param("offset") long offset,
                                        @Param("size") int size);

    long countOwned(@Param("ownerId") String ownerId,
                    @Param("workflowId") String workflowId,
                    @Param("status") String status);

    int transitionStatus(@Param("runId") String runId, @Param("expectedStatus") String expectedStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("expectedVersion") Long expectedVersion,
                         @Param("completedAt") LocalDateTime completedAt);

    Optional<WorkflowRunPo> selectByResumeTokenHash(@Param("resumeTokenHash") String resumeTokenHash);

    List<WorkflowRunPo> selectRecoverable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int tryClaim(@Param("runId") String runId, @Param("workerId") String workerId,
                 @Param("leaseUntil") LocalDateTime leaseUntil, @Param("now") LocalDateTime now);

    int renewLease(@Param("runId") String runId, @Param("workerId") String workerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    int updateState(@Param("run") WorkflowRunPo run, @Param("expectedVersion") Long expectedVersion);
}

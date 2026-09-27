package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowNodeRunPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow_node_run", queryFields = {})
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRunPo> {
    List<WorkflowNodeRunPo> selectByRunId(@Param("runId") String runId);

    Optional<WorkflowNodeRunPo> selectLatestAttempt(@Param("runId") String runId,
                                                    @Param("nodeId") String nodeId);

    int complete(@Param("nodeRunId") String nodeRunId, @Param("status") String status,
                 @Param("outputs") java.util.Map<String, Object> outputs,
                 @Param("errorInfo") java.util.Map<String, Object> errorInfo,
                 @Param("callId") String callId, @Param("completedAt") java.time.LocalDateTime completedAt,
                 @Param("latencyMs") Long latencyMs, @Param("expectedVersion") Long expectedVersion);
}

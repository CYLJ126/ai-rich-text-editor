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
}

package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowCheckpointPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow_checkpoint", queryFields = {})
public interface WorkflowCheckpointMapper extends BaseMapper<WorkflowCheckpointPo> {
    Optional<WorkflowCheckpointPo> selectLatest(@Param("runId") String runId);

    int deleteBeforeSequence(@Param("runId") String runId, @Param("sequence") Long sequence);
}

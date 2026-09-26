package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow", queryFields = {})
public interface WorkflowMapper extends BaseMapper<WorkflowPo> {
    Optional<WorkflowPo> selectByWorkflowId(@Param("workflowId") String workflowId);

    List<WorkflowPo> selectByOwnerId(@Param("ownerId") String ownerId);
}

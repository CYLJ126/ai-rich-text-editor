package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolEvaluationResultPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_evaluation_result", queryFields = {})
public interface ToolEvaluationResultMapper extends BaseMapper<ToolEvaluationResultPo> {
    int batchInsert(@Param("results") List<ToolEvaluationResultPo> results);

    List<ToolEvaluationResultPo> selectByRunId(@Param("runId") String runId);
}

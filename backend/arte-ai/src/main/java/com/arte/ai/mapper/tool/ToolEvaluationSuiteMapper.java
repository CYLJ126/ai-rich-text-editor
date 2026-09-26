package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolEvaluationSuitePo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_evaluation_suite", queryFields = {})
public interface ToolEvaluationSuiteMapper extends BaseMapper<ToolEvaluationSuitePo> {
    Optional<ToolEvaluationSuitePo> selectExact(@Param("suiteId") String suiteId,
                                                @Param("version") String version);
}

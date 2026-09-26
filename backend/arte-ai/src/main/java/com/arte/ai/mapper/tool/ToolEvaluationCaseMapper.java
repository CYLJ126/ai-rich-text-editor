package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolEvaluationCasePo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_evaluation_case", queryFields = {})
public interface ToolEvaluationCaseMapper extends BaseMapper<ToolEvaluationCasePo> {
    List<ToolEvaluationCasePo> selectBySuiteVersion(@Param("suiteId") String suiteId,
                                                    @Param("suiteVersion") String suiteVersion);
}

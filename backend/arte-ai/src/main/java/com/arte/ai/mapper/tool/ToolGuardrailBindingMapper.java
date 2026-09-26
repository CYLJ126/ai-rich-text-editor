package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolGuardrailBindingPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_guardrail_binding", queryFields = {})
public interface ToolGuardrailBindingMapper extends BaseMapper<ToolGuardrailBindingPo> {
    List<ToolGuardrailBindingPo> selectByScope(@Param("scopeType") String scopeType,
                                               @Param("scopeId") String scopeId);
}

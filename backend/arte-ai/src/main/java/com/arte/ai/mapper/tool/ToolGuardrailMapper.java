package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolGuardrailPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_guardrail", queryFields = {})
public interface ToolGuardrailMapper extends BaseMapper<ToolGuardrailPo> {
    List<ToolGuardrailPo> selectEnabled(@Param("ownerId") String ownerId);
}

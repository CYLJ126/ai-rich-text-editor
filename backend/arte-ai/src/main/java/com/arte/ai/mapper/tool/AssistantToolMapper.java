package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.AssistantToolPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_assistant_tool", queryFields = {})
public interface AssistantToolMapper extends BaseMapper<AssistantToolPo> {
    List<AssistantToolPo> selectEnabledByAssistantId(@Param("assistantId") Integer assistantId);
}

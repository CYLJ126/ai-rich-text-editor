package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_provider", queryFields = {})
public interface ToolProviderMapper extends BaseMapper<ToolProviderPo> {
    List<ToolProviderPo> selectEnabled();
}

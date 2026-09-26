package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolProviderPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_provider", queryFields = {})
public interface ToolProviderMapper extends BaseMapper<ToolProviderPo> {
    List<ToolProviderPo> selectEnabled();

    Optional<ToolProviderPo> selectByProviderId(@Param("providerId") String providerId);
}

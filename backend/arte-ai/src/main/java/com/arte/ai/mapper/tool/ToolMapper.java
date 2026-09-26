package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool", queryFields = {})
public interface ToolMapper extends BaseMapper<ToolPo> {
    Optional<ToolPo> selectByIdentity(@Param("namespace") String namespace, @Param("name") String name);

    List<ToolPo> selectByProviderId(@Param("providerId") String providerId);
}

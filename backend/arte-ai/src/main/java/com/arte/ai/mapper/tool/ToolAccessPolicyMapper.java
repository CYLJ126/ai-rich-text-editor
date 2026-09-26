package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolAccessPolicyPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_access_policy", queryFields = {})
public interface ToolAccessPolicyMapper extends BaseMapper<ToolAccessPolicyPo> {
    List<ToolAccessPolicyPo> selectEffective(@Param("ownerId") String ownerId,
                                             @Param("subjectType") String subjectType,
                                             @Param("subjectId") String subjectId,
                                             @Param("resourceType") String resourceType,
                                             @Param("resourceId") String resourceId);
}

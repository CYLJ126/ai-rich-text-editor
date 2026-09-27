package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.AssistantToolPo;
import com.arte.ai.pojo.tool.po.AssistantToolResolutionPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_assistant_tool", queryFields = {})
public interface AssistantToolMapper extends BaseMapper<AssistantToolPo> {
    List<AssistantToolPo> selectEnabledByAssistantId(@Param("assistantId") Integer assistantId);

    List<AssistantToolPo> selectByAssistantId(@Param("assistantId") Integer assistantId);

    int deleteByAssistantId(@Param("assistantId") Integer assistantId);

    List<AssistantToolResolutionPo> selectResolved(@Param("assistantId") Integer assistantId,
                                                   @Param("ownerId") String ownerId,
                                                   @Param("workspaceId") String workspaceId);
}

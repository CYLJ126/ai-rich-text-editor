package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolArtifactPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@MybatisParams(value = "arte_ai_tool_artifact", queryFields = {})
public interface ToolArtifactMapper extends BaseMapper<ToolArtifactPo> {
    List<ToolArtifactPo> selectByCallId(@Param("callId") String callId);

    int deleteExpired(@Param("now") LocalDateTime now);
}

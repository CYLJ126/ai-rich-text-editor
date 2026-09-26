package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_call_result", queryFields = {})
public interface ToolCallResultMapper extends BaseMapper<ToolCallResultPo> {
    Optional<ToolCallResultPo> selectByCallId(@Param("callId") String callId);

    Optional<ToolCallResultPo> selectByTaskId(@Param("taskId") String taskId);
}

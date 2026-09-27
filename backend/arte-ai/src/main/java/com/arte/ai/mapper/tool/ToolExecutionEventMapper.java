package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolExecutionEventPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_execution_event", queryFields = {})
public interface ToolExecutionEventMapper extends BaseMapper<ToolExecutionEventPo> {
    int batchInsert(@Param("events") List<ToolExecutionEventPo> events);

    List<ToolExecutionEventPo> selectByTraceId(@Param("traceId") String traceId);

    List<ToolExecutionEventPo> selectByCallId(@Param("callId") String callId);

    List<ToolExecutionEventPo> selectByTraceIdAndOwner(@Param("traceId") String traceId,
                                                       @Param("ownerId") String ownerId);

    List<ToolExecutionEventPo> selectRetriesByCallIds(@Param("callIds") List<String> callIds);
}

package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolMetricDailyPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@MybatisParams(value = "arte_ai_tool_metric_daily", queryFields = {})
public interface ToolMetricDailyMapper extends BaseMapper<ToolMetricDailyPo> {
    int upsert(ToolMetricDailyPo metric);

    List<ToolMetricDailyPo> selectRange(@Param("ownerId") String ownerId,
                                        @Param("toolId") String toolId,
                                        @Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate);
}

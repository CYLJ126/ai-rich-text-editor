package com.arte.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.ai.pojo.chat.TokenUsageDto;
import com.arte.core.annotations.MybatisParams;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@MybatisParams("arte_ai_token_usage")
public interface TokenUsageMapper extends BaseMapper<TokenUsageDto> {
    /**
     * 按日期/用户/模型聚合统计
     */
    List<TokenUsageDto> aggregateByCondition(@Param("userName") String userName,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate,
                                             @Param("modelId") String modelId,
                                             @Param("groupBy") String groupBy);
}
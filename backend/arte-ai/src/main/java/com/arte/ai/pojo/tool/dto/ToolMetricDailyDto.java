package com.arte.ai.pojo.tool.dto;

import com.arte.ai.pojo.tool.po.ToolMetricDailyPo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 工具每日统计实体 DTO
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class ToolMetricDailyDto extends ToolMetricDailyPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -3124741734955643749L;
}


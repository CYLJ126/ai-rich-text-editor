package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDate;

/**
 * AI 工具每日统计实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("arte_ai_tool_metric_daily")
public class ToolMetricDailyPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 7563977439536937651L;
    private LocalDate metricDate;
    private String ownerId;
    private String toolId;
    private String toolVersion;
    private Long callCount;
    private Long successCount;
    private Long failureCount;
    private Long deniedCount;
    private Long timeoutCount;
    private Long retryCount;
    private Long totalLatencyMs;
    private Long maxLatencyMs;
    private Long p50LatencyMs;
    private Long p95LatencyMs;
    private Long p99LatencyMs;
    private Long inputTokens;
    private Long outputTokens;
}

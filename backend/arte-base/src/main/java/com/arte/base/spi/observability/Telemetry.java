package com.arte.base.spi.observability;

/**
 * 指标与追踪。
 *
 * <p>采集指标和关联执行链路的追踪信息；允许采样，不替代耐久审计、执行记录或费用账本。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface Telemetry {
}

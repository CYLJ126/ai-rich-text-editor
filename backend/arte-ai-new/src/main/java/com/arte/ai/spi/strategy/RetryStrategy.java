package com.arte.ai.spi.strategy;

/**
 * 重试决策策略。
 *
 * <p>依据失败阶段、结果确定性、副作用和剩余预算决定重试；结果未知或可能产生副作用时先核对，不盲目再次执行。
 *
 * <p>设计依据：ARTE 顶层需求及设计，设计 §7。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface RetryStrategy {
}

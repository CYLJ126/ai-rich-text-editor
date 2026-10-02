package com.arte.ai.spi.strategy;

/**
 * 结果组合策略。
 *
 * <p>比较或组合符合契约的多个结果，保留来源及用量；多模型调用计入同一任务预算，不默认启动昂贵链路。
 *
 * <p>设计依据：ARTE 顶层需求及设计，设计 §7、顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ResultCompositionStrategy {
}

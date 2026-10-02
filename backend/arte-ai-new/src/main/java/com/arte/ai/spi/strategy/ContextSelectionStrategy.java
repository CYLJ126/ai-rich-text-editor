package com.arte.ai.spi.strategy;

/**
 * 上下文选择策略。
 *
 * <p>按目标范围、相关性、模型能力和内容预算选择与裁剪资料；保留来源及实际覆盖说明，不自行授予资源访问权。
 *
 * <p>设计依据：ARTE 顶层需求及设计，设计 §7、顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ContextSelectionStrategy {
}

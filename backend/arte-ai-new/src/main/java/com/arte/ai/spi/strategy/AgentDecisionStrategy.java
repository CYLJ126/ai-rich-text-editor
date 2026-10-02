package com.arte.ai.spi.strategy;

/**
 * Agent 决策策略。
 *
 * <p>根据目标、允许能力、上下文与反馈决定下一步或停止；返回可公开的决策摘要，不绕过统一执行治理。
 *
 * <p>设计依据：ARTE 顶层需求及设计，设计 §7、顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface AgentDecisionStrategy {
}

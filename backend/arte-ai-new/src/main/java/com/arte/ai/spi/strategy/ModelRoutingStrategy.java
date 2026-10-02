package com.arte.ai.spi.strategy;

/**
 * 模型路由策略。
 *
 * <p>按能力匹配、授权、发布版本、可用性和预算选择模型与绑定；替代选择须在用户允许范围内。
 *
 * <p>设计依据：ARTE 顶层需求及设计，设计 §7、顶层接口 §2.6。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ModelRoutingStrategy {
}

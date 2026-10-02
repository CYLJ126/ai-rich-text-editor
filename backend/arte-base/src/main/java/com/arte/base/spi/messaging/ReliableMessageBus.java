package com.arte.base.spi.messaging;

/**
 * 可靠消息传递。
 *
 * <p>提供发布、确认、重投、死信和重放能力；实现必须明确耐久、顺序、去重及保留语义，消费方仍需处理重复投递。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface ReliableMessageBus {
}

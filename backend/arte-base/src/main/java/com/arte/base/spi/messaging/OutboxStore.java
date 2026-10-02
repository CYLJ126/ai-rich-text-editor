package com.arte.base.spi.messaging;

/**
 * 事务消息存储。
 *
 * <p>在所属领域事务中登记待投递事件，并支持领取、投递记录、重试和死信处理；不持有数据库事务等待远端调用。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface OutboxStore {
}

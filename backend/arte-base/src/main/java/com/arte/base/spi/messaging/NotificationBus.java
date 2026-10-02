package com.arte.base.spi.messaging;

/**
 * 在线通知与唤醒。
 *
 * <p>向在线订阅者发送通知及唤醒信号；允许丢失，恢复依赖权威存储，不以通知代替可靠消息或耐久事件。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface NotificationBus {
}

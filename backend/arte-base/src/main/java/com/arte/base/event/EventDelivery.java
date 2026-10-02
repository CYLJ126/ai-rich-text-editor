package com.arte.base.event;

/**
 * 仅表明本地缓冲接收／断开数量，不证明订阅端消费或事件耐久存储。
 */
public record EventDelivery(int enqueuedSubscribers, int disconnectedSubscribers) {
    public EventDelivery {
        if (enqueuedSubscribers < 0 || disconnectedSubscribers < 0)
            throw new IllegalArgumentException("delivery counts must not be negative");
    }
}

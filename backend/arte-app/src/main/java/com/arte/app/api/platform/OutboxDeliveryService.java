package com.arte.app.api.platform;

import com.arte.core.pojo.platform.PublicEvent;

import java.time.Duration;
import java.util.List;

/**
 * app 内部 Outbox 租约投递接口，供 Redis 发布器使用；治理业务仍注入 core PublicOutboxStore。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface OutboxDeliveryService {

    record Delivery(PublicEvent event, String leaseToken) {
    }

    List<Delivery> claim(int limit, Duration lease);

    boolean acknowledge(Delivery delivery);

    boolean retry(Delivery delivery, Duration delay);
}

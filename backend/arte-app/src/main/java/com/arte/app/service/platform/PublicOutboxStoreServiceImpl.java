package com.arte.app.service.platform;

import com.arte.app.api.platform.OutboxDeliveryService;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformOutboxMapper;
import com.arte.app.pojo.platform.PlatformOutboxDto;
import com.arte.core.pojo.platform.PublicEvent;
import com.arte.core.spi.platform.PublicOutboxStore;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 事务事件发件箱服务
 * <p>
 * 数据库租约与令牌围栏；业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("publicOutboxStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class PublicOutboxStoreServiceImpl extends PlatformStoreSupport<PlatformOutboxMapper, PlatformOutboxDto> implements PublicOutboxStore, OutboxDeliveryService {

    private final PlatformJson json;

    public PublicOutboxStoreServiceImpl(
            @Qualifier("platformOutboxMapper") PlatformOutboxMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
            @Qualifier("platformJson") PlatformJson json) {
        super(mapper, tx);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void append(PublicEvent event) {
        tx.requireActive();
        try {
            var row = new PlatformOutboxDto();
            row.setId(event.id());
            row.setEventType(event.eventType());
            row.setEvent(json.write(event));
            row.setOccurredSec(event.occurredAt().getEpochSecond());
            row.setOccurredNano(event.occurredAt().getNano());
            if (!insertRecord(() -> baseMapper.insertEvent(row))) {
                var existing = Objects.requireNonNull(baseMapper.selectCurrent(event.id()));
                var winner = json.read(existing.getEvent(), PublicEvent.class);
                if (!winner.equals(event)) throw new IllegalStateException("Outbox event ID conflict");
            }
        } catch (RuntimeException | Error failure) {
            tx.markRollbackOnly();
            throw failure;
        }
    }

    @Override
    public List<Delivery> claim(int limit, Duration lease) {
        tx.requireActive();
        if (limit < 1 || limit > 100 || lease.isNegative() || lease.isZero())
            throw new IllegalArgumentException("Invalid lease batch");
        // 租约使用数据库时钟；提交领取事务后才由发布器访问 Redis。
        var now = baseMapper.databaseTime();
        var records = baseMapper.claimReady(limit).stream()
                .map(row -> json.read(row.getEvent(), PublicEvent.class)).toList();
        var deliveries = new ArrayList<Delivery>();
        for (var event : records) {
            String token = UUID.randomUUID().toString();
            write(() -> {
                int changed = baseMapper.lease(event.id(), token, now.plus(lease));
                if (changed != 1) throw new IllegalStateException("Outbox lease update failed");
                return changed;
            });
            deliveries.add(new Delivery(event, token));
        }
        return List.copyOf(deliveries);
    }

    @Override
    public boolean acknowledge(Delivery delivery) {
        return updateRecord(() -> baseMapper.acknowledge(delivery.event().id(), delivery.leaseToken()));
    }

    @Override
    public boolean retry(Delivery delivery, Duration delay) {
        if (delay.isNegative()) throw new IllegalArgumentException("Negative retry delay");
        tx.requireActive();
        var now = baseMapper.databaseTime();
        return updateRecord(() -> baseMapper.retry(delivery.event().id(), delivery.leaseToken(), now.plus(delay)));
    }
}

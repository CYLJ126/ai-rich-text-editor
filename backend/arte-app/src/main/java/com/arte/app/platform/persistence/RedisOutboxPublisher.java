package com.arte.app.platform.persistence;

import com.arte.app.api.platform.OutboxDeliveryService;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * At least once delivery. Consumers deduplicate by eventId; Redis is transport, not the durable
 * ledger.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class RedisOutboxPublisher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RedisOutboxPublisher.class);
    private static final AtomicLong OWNERS = new AtomicLong(-1);
    private final RedissonClient redis;
    private final OutboxDeliveryService outbox;
    private final PlatformPersistenceExecutor executor;
    private final PlatformJson json;
    private final PlatformPersistenceProperties properties;
    private Disposable polling;

    public RedisOutboxPublisher(
            RedissonClient redis,
            OutboxDeliveryService outbox,
            PlatformPersistenceExecutor executor,
            PlatformJson json,
            PlatformPersistenceProperties properties) {
        this.redis = redis;
        this.outbox = outbox;
        this.executor = executor;
        this.json = json;
        this.properties = properties;
    }

    private record Lock(RLock lock, long owner, boolean acquired) {
    }

    public Mono<Integer> drainOnce() {
        return Mono.defer(
                () -> {
                    RLock lock = redis.getLock(properties.redisPrefix() + "outbox:publisher");
                    long owner = OWNERS.getAndDecrement();
                    return Mono.usingWhen(
                            Mono.fromFuture(
                                            lock.tryLockAsync(
                                                            0,
                                                            properties.lease().toMillis(),
                                                            TimeUnit.MILLISECONDS,
                                                            owner)
                                                    .toCompletableFuture(),
                                            true)
                                    .map(acquired -> new Lock(lock, owner, acquired)),
                            handle -> handle.acquired() ? publishBatch() : Mono.just(0),
                            this::release,
                            (handle, failure) -> release(handle),
                            this::release);
                });
    }

    private Mono<Void> release(Lock handle) {
        if (!handle.acquired()) return Mono.empty();
        return Mono.fromFuture(
                        handle.lock().unlockAsync(handle.owner()).toCompletableFuture(), true)
                .onErrorResume(failure -> Mono.empty()); // Lease may already have expired; DB lease
        // tokens fence late
        // completions.
    }

    private Mono<Integer> publishBatch() {
        return executor.inTransaction(
                        () -> outbox.claim(properties.outboxBatchSize(), properties.lease()))
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::deliver, 1)
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> deliver(OutboxDeliveryService.Delivery delivery) {
        return send(delivery.event())
                .flatMap(id -> executor.inTransaction(() -> outbox.acknowledge(delivery)))
                .map(acknowledged -> acknowledged ? 1 : 0)
                .onErrorResume(
                        failure -> {
                            LOG.warn(
                                    "Platform event delivery deferred, eventId={}, type={}",
                                    delivery.event().id(),
                                    failure.getClass().getSimpleName());
                            return executor.inTransaction(
                                            () -> outbox.retry(delivery, properties.retryDelay()))
                                    .thenReturn(0);
                        });
    }

    private Mono<org.redisson.api.stream.StreamMessageId> send(
            com.arte.core.pojo.platform.PublicEvent event) {
        return Mono.defer(
                () -> {
                    RStream<String, String> stream =
                            redis.getStream(
                                    properties.redisPrefix() + "events", StringCodec.INSTANCE);
                    var attributes = Map.of("eventId", event.id(), "event", json.write(event));
                    var future = stream.addAsync(StreamAddArgs.entries(attributes));
                    return Mono.fromFuture(future.toCompletableFuture(), true);
                });
    }

    public synchronized void start() {
        if (polling != null) return;
        polling =
                Flux.interval(properties.pollInterval())
                        .onBackpressureDrop()
                        .concatMap(
                                tick ->
                                        drainOnce()
                                                .onErrorResume(
                                                        failure -> {
                                                            LOG.warn(
                                                                    "Platform outbox unavailable,"
                                                                            + " type={}",
                                                                    failure.getClass()
                                                                            .getSimpleName());
                                                            return Mono.empty();
                                                        }),
                                1)
                        .subscribe();
    }

    @Override
    public synchronized void close() {
        if (polling != null) {
            polling.dispose();
            polling = null;
        }
    }
}

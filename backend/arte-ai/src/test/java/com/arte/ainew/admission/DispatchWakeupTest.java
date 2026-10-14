package com.arte.ainew.admission;

import com.arte.ainew.application.execution.*;
import com.arte.ainew.config.NewAiEventProperties;
import org.junit.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.listener.StatusListener;
import org.redisson.misc.CompletableFutureWrapper;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * 受控 Redis Topic 验证提示协议与异步广播；不访问外部 Redis。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 20:06 ✾
 */
public class DispatchWakeupTest {
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static final class Wire {
        final AtomicInteger ids = new AtomicInteger(), publications = new AtomicInteger();
        final Map<Integer, MessageListener<String>> messages = new ConcurrentHashMap<>();
        final Map<Integer, StatusListener> statuses = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        RedissonClient client() {
            var topic = (RTopic) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{RTopic.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "addListenerAsync" -> {
                        int id = ids.incrementAndGet();
                        if (args.length == 1) statuses.put(id, (StatusListener) args[0]);
                        else messages.put(id, (MessageListener<String>) args[1]);
                        yield new CompletableFutureWrapper<>(CompletableFuture.completedFuture(id));
                    }
                    case "removeListenerAsync" -> {
                        for (int id : (Integer[]) args[0]) {
                            messages.remove(id);
                            statuses.remove(id);
                        }
                        yield new CompletableFutureWrapper<>(CompletableFuture.completedFuture(null));
                    }
                    case "publishAsync" -> {
                        publications.incrementAndGet();
                        for (var listener : messages.values()) listener.onMessage("test", (String) args[0]);
                        yield new CompletableFutureWrapper<>(CompletableFuture.completedFuture((long) messages.size()));
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            return (RedissonClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{RedissonClient.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getTopic")) return topic;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    @Test
    public void httpOnlyNodeBroadcastWakesRemoteWorkerWithoutRebroadcastAndReconnectChecksAgain() throws Exception {
        var wire = new Wire();
        var a = new LocalDispatchNotifier();
        var b = new LocalDispatchNotifier();
        var options = new NewAiEventProperties(NewAiEventProperties.Transport.REDIS, "test", Duration.ofMillis(100));
        var busA = new RedisExecutionEventBroadcast(wire.client(), new LocalExecutionEventNotifier(), options, null, a);
        var busB = new RedisExecutionEventBroadcast(wire.client(), new LocalExecutionEventNotifier(), options, null, b);
        var timer = Schedulers.newSingle("dispatch-wakeup-test");
        var relay = new DispatchWakeupPublisher(a, busA, timer);
        var remote = new AtomicInteger();
        var watch = b.wakeups().subscribe(ignored -> remote.incrementAndGet());
        try {
            busA.start();
            busB.start();
            relay.start();
            assertTrue(busA.isReady());
            assertTrue(busB.isReady());
            int before = remote.get();
            a.committed();
            await(() -> remote.get() > before);
            assertEquals(1, wire.publications.get());
            int reconnect = remote.get();
            wire.statuses.values().forEach(listener -> listener.onSubscribe("test"));
            assertTrue(remote.get() > reconnect);
            assertEquals(1, wire.publications.get());
            busB.stop();
            int stopped = remote.get();
            busA.publishDispatchWakeup().block(Duration.ofSeconds(1));
            assertEquals(stopped, remote.get());
        } finally {
            relay.stop();
            busA.stop();
            busB.stop();
            watch.dispose();
            timer.dispose();
        }
        assertTrue(wire.messages.isEmpty());
        assertTrue(wire.statuses.isEmpty());
    }

    @Test
    public void delayedPublicationCoalescesCommitHintsAndStopUnsubscribes() throws Exception {
        var notifier = new LocalDispatchNotifier();
        var blocked = Sinks.<Void>empty();
        var calls = new AtomicInteger();
        var timer = Schedulers.newSingle("dispatch-wakeup-test");
        var relay = new DispatchWakeupPublisher(notifier, new ExecutionEventBroadcast() {
            public Mono<Void> publish(com.arte.ainew.pojo.execution.OutboxMessage message) {
                return Mono.empty();
            }

            public Mono<Void> publishDispatchWakeup() {
                return calls.incrementAndGet() == 1 ? blocked.asMono() : Mono.empty();
            }
        }, timer);
        try {
            relay.start();
            notifier.committed();
            await(() -> calls.get() == 1);
            for (int i = 0; i < 100; i++) notifier.committed();
            assertEquals(1, calls.get());
            blocked.tryEmitEmpty();
            await(() -> calls.get() > 1);
            var barrier = new CountDownLatch(1);
            timer.schedule(barrier::countDown);
            assertTrue(barrier.await(5, TimeUnit.SECONDS));
            assertTrue("积压提示必须有界合并", calls.get() <= 4);
            relay.stop();
            int stopped = calls.get();
            notifier.committed();
            assertEquals(stopped, calls.get());
        } finally {
            relay.stop();
            timer.dispose();
        }
    }

    @Test
    public void publicationFailureKeepsRelayAliveAndStoppingCancelsOutstandingPublication() throws Exception {
        var notifier = new LocalDispatchNotifier();
        var calls = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var subscribed = new AtomicBoolean();
        var timer = Schedulers.newSingle("dispatch-wakeup-test");
        var relay = new DispatchWakeupPublisher(notifier, new ExecutionEventBroadcast() {
            public Mono<Void> publish(com.arte.ainew.pojo.execution.OutboxMessage message) {
                return Mono.empty();
            }

            public Mono<Void> publishDispatchWakeup() {
                return calls.incrementAndGet() == 1 ? Mono.error(new IllegalStateException("模拟断线"))
                        : Mono.<Void>never().doOnSubscribe(ignored -> subscribed.set(true)).doOnCancel(() -> cancelled.set(true));
            }
        }, timer);
        try {
            relay.start();
            notifier.committed();
            await(() -> calls.get() == 1);
            notifier.committed();
            await(subscribed::get);
            assertEquals(2, calls.get());
            assertTrue(relay.isRunning());
            relay.stop();
            assertTrue(cancelled.get());
            assertFalse(relay.isRunning());
        } finally {
            relay.stop();
            timer.dispose();
        }
    }
}

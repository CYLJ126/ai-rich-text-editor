package com.arte.ainew.execution;

import com.arte.ainew.application.execution.ExecutionEventBroadcast;
import com.arte.ainew.application.execution.LiveTextNotifier;
import com.arte.ainew.application.execution.LiveTextPublisher;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.pojo.execution.OutboxMessage;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class LiveTextNotifierTest {
    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "alice");

    private LiveTextDelta delta(int offset) {
        return new LiveTextDelta(owner, "invocation", "attempt", offset, "字");
    }

    @Test
    public void isolatesOwnersAndInvocationsAndDisposesListeners() {
        var bus = new LiveTextNotifier();
        StepVerifier.create(bus.watch(owner, "invocation"))
                .then(() -> {
                    bus.emit(new LiveTextDelta(new ExecutionOwner("tenant", "workspace", "bob"), "invocation", "attempt", 0, "不可见"));
                    bus.emit(new LiveTextDelta(owner, "another", "attempt", 0, "不可见"));
                    bus.emit(delta(0));
                }).expectNext(delta(0)).thenCancel().verify(Duration.ofSeconds(2));
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    public void shortBurstPreservesEveryFragmentWithoutWaitingForDurableOutput() {
        var bus = new LiveTextNotifier();
        StepVerifier.create(bus.watch(owner, "invocation"), 0)
                .then(() -> {
                    for (int i = 0; i < 1000; i++) bus.emit(delta(i));
                })
                .thenRequest(1000).expectNextSequence(java.util.stream.IntStream.range(0, 1000).mapToObj(this::delta).toList())
                .thenCancel().verify(Duration.ofSeconds(2));
    }

    @Test
    public void stalledSubscriberHasBoundedBufferAndCanRecoverDiscardedPrefixFromDurableOutput() {
        var bus = new LiveTextNotifier();
        StepVerifier.create(bus.watch(owner, "invocation"), 0)
                .then(() -> {
                    for (int i = 0; i < LiveTextNotifier.CAPACITY + 10; i++) bus.emit(delta(i));
                })
                .thenRequest(LiveTextNotifier.CAPACITY)
                .expectNextSequence(java.util.stream.IntStream.range(10, LiveTextNotifier.CAPACITY + 10).mapToObj(this::delta).toList())
                .thenCancel().verify(Duration.ofSeconds(2));
    }

    @Test
    public void stalledBroadcastDoesNotBlockLocalTextOrGrowUnbounded() throws Exception {
        var bus = new LiveTextNotifier();
        var sent = new CopyOnWriteArrayList<LiveTextDelta>();
        var received = new CopyOnWriteArrayList<LiveTextDelta>();
        var gate = Sinks.<Void>one();
        var transport = new ExecutionEventBroadcast() {
            public Mono<Void> publish(OutboxMessage ignored) {
                return Mono.empty();
            }

            public Mono<Void> publishText(LiveTextDelta value) {
                sent.add(value);
                return gate.asMono();
            }
        };
        var publisher = new LiveTextPublisher(bus, transport);
        var watch = bus.watch(owner, "invocation").subscribe(received::add);
        publisher.start();
        try {
            bus.emit(delta(0));
            long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (sent.isEmpty() && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(1, sent.size());
            for (int i = 1; i < 1000; i++) bus.emit(delta(i));
            assertEquals(1000, received.size());
            assertEquals(1, sent.size());
            gate.tryEmitEmpty();
            end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (sent.size() < LiveTextPublisher.CAPACITY && System.nanoTime() < end) Thread.sleep(5);
            // 队列之外最多只有 concatMap 与 publishOn 各自预取的一个片段。
            assertTrue(sent.size() <= LiveTextPublisher.CAPACITY + 3);
            assertTrue(sent.size() >= LiveTextPublisher.CAPACITY);
        } finally {
            publisher.stop();
            watch.dispose();
        }
        assertFalse(publisher.isRunning());
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    public void validatesOffsetAndFragmentSize() {
        assertThrows(IllegalArgumentException.class, () -> new LiveTextDelta(owner, "invocation", "attempt", -1, "字"));
        assertThrows(IllegalArgumentException.class, () -> new LiveTextDelta(owner, "invocation", "attempt", 0, "字".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new LiveTextDelta(owner, "invocation", "attempt", 1_000_000, "字"));
    }
}

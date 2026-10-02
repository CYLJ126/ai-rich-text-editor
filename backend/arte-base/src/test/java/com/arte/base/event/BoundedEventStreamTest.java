package com.arte.base.event;

import com.arte.base.model.execution.ExecutionEvent;
import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class BoundedEventStreamTest {
    ExecutionEvent<String> event(long sequence) {
        return new ExecutionEvent<>("execution", "attempt", sequence, "output", Instant.now(), "chunk-" + sequence, null);
    }

    static class Consumer implements Flow.Subscriber<ExecutionEvent<String>> {
        Flow.Subscription subscription;
        final List<Long> received = new CopyOnWriteArrayList<>();
        final CountDownLatch next = new CountDownLatch(1), ended = new CountDownLatch(1);
        volatile Throwable error;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
        }

        public void onNext(ExecutionEvent<String> event) {
            received.add(event.sequence());
            next.countDown();
        }

        public void onComplete() {
            ended.countDown();
        }

        public void onError(Throwable error) {
            this.error = error;
            ended.countDown();
        }
    }

    @Test
    public void honorsDemandAndCompletesOnlyAfterBufferedEventsAreConsumed() throws Exception {
        try (var stream = new BoundedEventStream<String>("execution", "attempt", 2, 2, 1)) {
            var consumer = new Consumer();
            stream.subscribe(consumer);
            stream.emit(event(0));
            stream.emit(event(1));
            stream.complete();
            assertTrue(consumer.received.isEmpty());
            assertEquals(1, consumer.ended.getCount());
            consumer.subscription.request(2);
            assertTrue(consumer.ended.await(2, TimeUnit.SECONDS));
            assertNull(consumer.error);
            assertEquals(List.of(0L, 1L), consumer.received);
        }
    }

    @Test
    public void overflowsDisconnectOnlySlowConsumerAndDoesNotBlockProducerOrHealthyConsumer() throws Exception {
        try (var stream = new BoundedEventStream<String>("execution", "attempt", 1, 2, 2)) {
            var slow = new Consumer();
            var healthy = new Consumer();
            stream.subscribe(slow);
            stream.subscribe(healthy);
            healthy.subscription.request(Long.MAX_VALUE);
            stream.emit(event(0));
            assertTrue(healthy.next.await(2, TimeUnit.SECONDS));
            var delivered = stream.emit(event(1));
            assertEquals(1, delivered.disconnectedSubscribers());
            assertTrue(slow.ended.await(2, TimeUnit.SECONDS));
            assertNotNull(slow.error);
            stream.complete();
            assertTrue(healthy.ended.await(2, TimeUnit.SECONDS));
            assertNull(healthy.error);
            assertEquals(List.of(0L, 1L), healthy.received);
        }
    }

    @Test
    public void boundedSubscribersInvalidDemandAndIdentityChecksAreExplicit() throws Exception {
        try (var stream = new BoundedEventStream<String>("execution", "attempt", 2, 1, 1)) {
            var first = new Consumer();
            var second = new Consumer();
            stream.subscribe(first);
            stream.subscribe(second);
            assertTrue(second.ended.await(2, TimeUnit.SECONDS));
            assertNotNull(second.error);
            first.subscription.request(0);
            assertTrue(first.ended.await(2, TimeUnit.SECONDS));
            assertNotNull(first.error);
            stream.emit(event(0));
            assertThrows(IllegalArgumentException.class, () -> stream.emit(event(0)));
            assertThrows(IllegalArgumentException.class, () -> stream.emit(new ExecutionEvent<>("other", "attempt", 1, "output", Instant.now(), "payload", null)));
        }
    }

    @Test
    public void subscriberFailureAndQueuedCancellationDoNotPoisonStream() throws Exception {
        try (var stream = new BoundedEventStream<String>("execution", "attempt", 2, 2, 1)) {
            var broken = new Consumer() {
                @Override
                public void onNext(ExecutionEvent<String> event) {
                    super.onNext(event);
                    throw new IllegalStateException("broken observer");
                }
            };
            stream.subscribe(broken);
            broken.subscription.request(Long.MAX_VALUE);
            stream.emit(event(0));
            assertTrue(broken.next.await(2, TimeUnit.SECONDS));
            broken.subscription.cancel();
            var healthy = new Consumer();
            stream.subscribe(healthy);
            healthy.subscription.request(Long.MAX_VALUE);
            stream.emit(event(1));
            stream.complete();
            assertTrue(healthy.ended.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1L), healthy.received);
        }
    }
}

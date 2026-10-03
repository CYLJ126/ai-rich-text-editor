package com.arte.app.ainew;

import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只从耐久事件表补读；有界订阅和发送线程隔离慢连接，断开订阅不取消模型工作。
 */
public final class ChatEventStreams implements AutoCloseable {
    public record Event(String executionId, long sequence, ExecutionStatus status, String textDelta, ModelResult result,
                        ExecutionError error) {
    }

    private final NewChatCallService service;
    private final Semaphore slots;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor writers;
    private final ScheduledExecutorService timer;
    private final long timeoutMillis;
    private final AutoCloseable eventListener;
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ChatEventStreams(NewChatCallService service, int capacity, Duration timeout) {
        this(service, capacity, timeout, null);
    }

    public ChatEventStreams(NewChatCallService service, int capacity, Duration timeout, JdbcModelExecutionStore ledger) {
        if (capacity < 1 || capacity > 256 || timeout.compareTo(Duration.ofSeconds(10)) < 0 || timeout.compareTo(Duration.ofMinutes(3)) > 0)
            throw new IllegalArgumentException("invalid chat stream limits");
        this.service = service;
        this.slots = new Semaphore(capacity);
        this.timeoutMillis = timeout.toMillis();
        writers = new ThreadPoolExecutor(Math.min(4, capacity), Math.min(4, capacity), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> daemon(task, "arte-chat-stream-send"), new ThreadPoolExecutor.AbortPolicy());
        timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "arte-chat-stream-poll"));
        eventListener = ledger == null ? () -> {
        } : ledger.watchEvents(id -> {
            for (var subscription : subscriptions)
                if (subscription.observation.executionId().equals(id)) schedule(subscription);
        });
        long interval = ledger == null ? 200 : 2000;
        timer.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public synchronized SseEmitter open(NewChatCallService.Observation observation, long after) {
        if (stopped.get()) throw new IllegalStateException("stream observer stopped");
        if (after < -1) throw new IllegalArgumentException("invalid event cursor");
        if (!slots.tryAcquire())
            throw new BaseException(ExecutionError.of(CommonErrorCode.BUSY, "chat-stream-capacity", true, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
        var subscription = new Subscription(observation, after);
        subscriptions.add(subscription);
        subscription.emitter.onCompletion(subscription::remove);
        subscription.emitter.onTimeout(subscription::complete);
        subscription.emitter.onError(error -> subscription.remove());
        schedule(subscription);
        return subscription.emitter;
    }

    private void tick() {
        for (var subscription : subscriptions) {
            if (System.nanoTime() - subscription.opened >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
                subscription.complete();
                continue;
            }
            schedule(subscription);
        }
    }

    private void schedule(Subscription subscription) {
        subscription.dirty.set(true);
        if (subscription.closed.get() || !subscription.pending.compareAndSet(false, true)) return;
        try {
            writers.execute(subscription::send);
        } catch (RejectedExecutionException busy) {
            subscription.pending.set(false);
        }
    }

    private final class Subscription {
        final NewChatCallService.Observation observation;
        final SseEmitter emitter = new SseEmitter(timeoutMillis);
        final AtomicBoolean pending = new AtomicBoolean(), closed = new AtomicBoolean(), dirty = new AtomicBoolean();
        final long opened = System.nanoTime();
        long cursor, lastHeartbeat = opened;

        Subscription(NewChatCallService.Observation observation, long after) {
            this.observation = observation;
            this.cursor = after;
        }

        void send() {
            try {
                if (closed.get()) return;
                dirty.set(false);
                var events = service.events(observation, cursor, 64);
                if (events.size() == 64) dirty.set(true);
                for (var event : events) {
                    if (closed.get()) return;
                    var value = event.payload();
                    emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("model").data(
                            new Event(event.executionId(), event.sequence(), value.status(), value.textDelta(), value.result(), value.error())));
                    cursor = event.sequence();
                    if (value.status() != ExecutionStatus.ACCEPTED && value.status() != ExecutionStatus.RUNNING) {
                        complete();
                        return;
                    }
                }
                if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(10)) {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    lastHeartbeat = System.nanoTime();
                }
            } catch (IOException disconnected) {
                complete();
            } catch (RuntimeException failed) {
                var error = failed instanceof BaseException known ? known.error() : ExecutionError.of(
                        failed instanceof org.springframework.security.access.AccessDeniedException ? CommonErrorCode.UNAUTHORIZED : CommonErrorCode.POLICY_UNAVAILABLE,
                        "chat-stream", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null);
                try {
                    emitter.send(SseEmitter.event().name("failure").data(error));
                } catch (IOException | RuntimeException ignored) {
                }
                complete();
            } finally {
                pending.set(false);
                if (dirty.get() && !closed.get()) schedule(this);
            }
        }

        void remove() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
                slots.release();
            }
        }

        void complete() {
            remove();
            emitter.complete();
        }
    }

    public synchronized void close() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            eventListener.close();
        } catch (Exception ignored) {
        }
        timer.shutdownNow();
        subscriptions.forEach(Subscription::complete);
        writers.shutdownNow();
    }
}

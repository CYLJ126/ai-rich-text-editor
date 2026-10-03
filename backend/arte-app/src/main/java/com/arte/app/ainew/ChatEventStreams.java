package com.arte.app.ainew;

import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.execution.ModelEvent;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

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
                if (subscription.executionId.equals(id)) schedule(subscription);
        });
        long interval = ledger == null ? 200 : 2000;
        timer.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public SseEmitter open(NewChatCallService.Observation observation, long after) {
        return open(observation.executionId(), after, (cursor, limit) -> service.events(observation, cursor, limit));
    }

    /**
     * 聊天与独立动作共用耐久事件传输；reader 每批重新校验所属场景和当前权限。
     */
    public synchronized SseEmitter open(String executionId, long after,
                                        BiFunction<Long, Integer, List<ExecutionEvent<ModelEvent>>> reader) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(reader, "reader");
        if (stopped.get()) throw new IllegalStateException("stream observer stopped");
        if (after < -1) throw new IllegalArgumentException("invalid event cursor");
        if (!slots.tryAcquire())
            throw new BaseException(ExecutionError.of(CommonErrorCode.BUSY, "chat-stream-capacity", true, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
        var subscription = new Subscription(executionId, reader, after);
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
        final String executionId;
        final BiFunction<Long, Integer, List<ExecutionEvent<ModelEvent>>> reader;
        final SseEmitter emitter = new SseEmitter(timeoutMillis);
        final AtomicBoolean pending = new AtomicBoolean(), closed = new AtomicBoolean(), dirty = new AtomicBoolean();
        final long opened = System.nanoTime();
        long cursor, lastHeartbeat = opened;

        Subscription(String executionId, BiFunction<Long, Integer, List<ExecutionEvent<ModelEvent>>> reader, long after) {
            this.executionId = executionId;
            this.reader = reader;
            this.cursor = after;
        }

        void send() {
            try {
                if (closed.get()) return;
                dirty.set(false);
                var events = reader.apply(cursor, 64);
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

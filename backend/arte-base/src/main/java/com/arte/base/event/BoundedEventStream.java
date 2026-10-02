package com.arte.base.event;

import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.validation.ContractChecks;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 单个执行／尝试的本地有界 Flow 流；按需消费，慢订阅者断开；不保存历史、不承担可靠重放。
 */
public final class BoundedEventStream<T> implements Flow.Publisher<ExecutionEvent<T>>, AutoCloseable {
    private final Object lock = new Object();
    private final String executionId, attemptId;
    private final int capacity, maxSubscribers;
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final ThreadPoolExecutor dispatch;
    private boolean completed;
    private long lastSequence = -1;

    public BoundedEventStream(String executionId, String attemptId, int capacity, int maxSubscribers, int dispatchThreads) {
        this.executionId = ContractChecks.identifier(executionId, "executionId");
        this.attemptId = ContractChecks.optionalIdentifier(attemptId, "attemptId");
        if (capacity <= 0 || maxSubscribers <= 0 || dispatchThreads <= 0)
            throw new IllegalArgumentException("invalid stream bounds");
        this.capacity = capacity;
        this.maxSubscribers = maxSubscribers;
        dispatch = new ThreadPoolExecutor(dispatchThreads, dispatchThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxSubscribers), task -> {
            var thread = new Thread(task, "arte-events");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ExecutionEvent<T>> subscriber) {
        ContractChecks.required(subscriber, "subscriber");
        var subscription = new Subscription(subscriber);
        boolean ended, full;
        synchronized (lock) {
            ended = completed;
            full = subscriptions.size() >= maxSubscribers;
            if (!ended && !full) subscriptions.add(subscription);
        }
        try {
            subscriber.onSubscribe(subscription);
        } catch (Throwable broken) {
            subscription.cancel();
            return;
        }
        if (ended || full) {
            subscription.cancel();
            try {
                if (ended) subscriber.onComplete();
                else subscriber.onError(new IllegalStateException("arte.common.busy"));
            } catch (Throwable broken) { /* 不让坏订阅者影响其他订阅者 */ }
        } else synchronized (lock) {
            subscription.ready = true;
            subscription.schedule();
        }
    }

    public EventDelivery emit(ExecutionEvent<T> event) {
        ContractChecks.required(event, "event");
        int enqueued = 0, disconnected = 0;
        synchronized (lock) {
            if (completed) throw new IllegalStateException("event stream completed");
            if (!executionId.equals(event.executionId()) || !Objects.equals(attemptId, event.attemptId()) || event.sequence() <= lastSequence) {
                throw new IllegalArgumentException("event stream identity or sequence mismatch");
            }
            lastSequence = event.sequence();
            for (var subscription : new ArrayList<>(subscriptions)) {
                if (subscription.cancelled || subscription.failure != null) continue;
                if (subscription.queue.size() >= capacity) {
                    subscription.queue.clear();
                    subscription.failure = new IllegalStateException("arte.common.busy");
                    disconnected++;
                } else {
                    subscription.queue.addLast(event);
                    enqueued++;
                }
                subscription.schedule();
            }
        }
        return new EventDelivery(enqueued, disconnected);
    }

    /**
     * 按背压排空后完成；订阅者仍需继续 request 或主动 cancel。
     */
    public void complete() {
        synchronized (lock) {
            if (completed) return;
            completed = true;
            subscriptions.forEach(Subscription::schedule);
            if (subscriptions.isEmpty()) dispatch.shutdown();
        }
    }

    /**
     * 强制关闭丢弃本地缓冲；真实重放必须从耐久事件存储读取。
     */
    @Override
    public void close() {
        synchronized (lock) {
            completed = true;
            for (var subscription : subscriptions) {
                subscription.queue.clear();
                subscription.schedule();
            }
            if (subscriptions.isEmpty()) dispatch.shutdown();
        }
    }

    private final class Subscription implements Flow.Subscription {
        final Flow.Subscriber<? super ExecutionEvent<T>> subscriber;
        final ArrayDeque<ExecutionEvent<T>> queue = new ArrayDeque<>();
        long demand;
        boolean running, ready, cancelled;
        Throwable failure;
        final Runnable drainTask = this::drain;

        Subscription(Flow.Subscriber<? super ExecutionEvent<T>> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long amount) {
            synchronized (lock) {
                if (cancelled) return;
                if (amount <= 0) {
                    queue.clear();
                    failure = new IllegalArgumentException("Flow demand must be positive");
                } else demand = Long.MAX_VALUE - demand < amount ? Long.MAX_VALUE : demand + amount;
                schedule();
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                remove();
            }
        }

        void remove() {
            cancelled = true;
            queue.clear();
            subscriptions.remove(this);
            dispatch.remove(drainTask);
            if (completed && subscriptions.isEmpty()) dispatch.shutdown();
        }

        void schedule() {
            if (!ready || cancelled || running || (failure == null && !(completed && queue.isEmpty()) && (demand == 0 || queue.isEmpty())))
                return;
            running = true;
            dispatch.execute(drainTask);
        }

        void drain() {
            for (; ; ) {
                ExecutionEvent<T> event = null;
                Throwable error;
                boolean finish;
                synchronized (lock) {
                    if (cancelled) {
                        running = false;
                        return;
                    }
                    error = failure;
                    finish = completed && queue.isEmpty();
                    if (error != null || finish) {
                        running = false;
                        remove();
                    } else if (demand > 0 && !queue.isEmpty()) {
                        event = queue.removeFirst();
                        if (demand != Long.MAX_VALUE) demand--;
                    } else {
                        running = false;
                        return;
                    }
                }
                try {
                    if (error != null) {
                        subscriber.onError(error);
                        return;
                    }
                    if (finish) {
                        subscriber.onComplete();
                        return;
                    }
                    subscriber.onNext(event);
                } catch (Throwable broken) {
                    cancel();
                    return;
                }
            }
        }
    }
}

package com.arte.ainew.application.execution;

import com.arte.ainew.common.execution.LiveTextDelta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 有界、尽力而为的跨实例文字发布。发送失败不重执行模型，不影响耐久 EVENT Outbox；缺失预览由重放修复。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:00 ✾
 */
@Slf4j
public final class LiveTextPublisher implements SmartLifecycle {
    public static final int CAPACITY = 256;
    private final LiveTextNotifier notifier;
    private final ExecutionEventBroadcast broadcast;
    private volatile Sinks.Many<LiveTextDelta> queue;
    private Disposable subscription;

    public LiveTextPublisher(LiveTextNotifier notifier, ExecutionEventBroadcast broadcast) {
        this.notifier = notifier;
        this.broadcast = broadcast;
    }

    private synchronized void offer(LiveTextDelta delta) {
        if (queue != null) queue.tryEmitNext(delta); // 满队列丢弃预览，不阻塞模型回调或建立无界等待。
    }

    @Override
    public synchronized void start() {
        if (queue != null) return;
        queue = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(CAPACITY));
        subscription = queue.asFlux().publishOn(Schedulers.parallel(), 1)
                .concatMap(delta -> Mono.defer(() -> broadcast.publishText(delta)).onErrorResume(error -> {
                    log.debug("AI live text broadcast unavailable, invocationId={}, attemptId={}, type={}",
                            delta.executionId(), delta.attemptId(), error.getClass().getName());
                    return Mono.empty();
                }), 1).subscribe();
        notifier.setBroadcaster(this::offer);
    }

    @Override
    public synchronized void stop() {
        notifier.setBroadcaster(ignored -> {
        });
        if (subscription != null) subscription.dispose();
        queue = null;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return queue != null;
    }

}

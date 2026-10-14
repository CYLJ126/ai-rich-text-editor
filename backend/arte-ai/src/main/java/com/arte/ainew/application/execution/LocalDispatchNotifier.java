package com.arte.ainew.application.execution;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 派发检查提示，不携带任务或发送许可；数据库领取仍是唯一仲裁。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 20:06 ✾
 */
public final class LocalDispatchNotifier {
    private final Set<FluxSink<Long>> workers = new HashSet<>();
    private final Set<FluxSink<Long>> publishers = new HashSet<>();

    /**
     * 提交成功后唤醒本地 Worker，并异步提示广播器；回滚不得调用。
     */
    public void committed() {
        wakeWorker();
        emit(publishers);
    }

    /**
     * 空闲槽位、远端提示或重连只触发本地检查，不再次广播。
     */
    public void wakeWorker() {
        emit(workers);
    }

    /**
     * 登记后立即检查，之后按 LATEST 合并重复提示；取消订阅自动注销。
     */
    public Flux<Long> wakeups() {
        return listen(workers, true);
    }

    /**
     * 提交提示广播流；启动不产生提示，不保留历史，遗漏由恢复扫描处理。
     */
    public Flux<Long> commits() {
        return listen(publishers, false);
    }

    private Flux<Long> listen(Set<FluxSink<Long>> listeners, boolean initial) {
        return Flux.create(sink -> {
            synchronized (listeners) {
                listeners.add(sink);
            }
            sink.onDispose(() -> {
                synchronized (listeners) {
                    listeners.remove(sink);
                }
            });
            if (initial) sink.next(0L);
        }, FluxSink.OverflowStrategy.LATEST);
    }

    private void emit(Set<FluxSink<Long>> listeners) {
        List<FluxSink<Long>> copy;
        synchronized (listeners) {
            copy = List.copyOf(listeners);
        }
        copy.forEach(sink -> sink.next(0L));
    }
}

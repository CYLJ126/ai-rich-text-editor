package com.arte.ainew.application.execution;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.LiveTextDelta;
import lombok.Setter;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 只负责实时文字预览。模型回调不等待 JDBC、Redis 或浏览器；每个观看者有界缓存短暂突发，
 * 缺口由耐久事件补齐。授权及连接期限由事件服务检查；按完整 owner/id 隔离，不缓存整段正文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:00 ✾
 */
public final class LiveTextNotifier {

    public static final int CAPACITY = 1024;

    private record Listener(ExecutionOwner owner, String id, FluxSink<LiveTextDelta> sink) {
    }

    private final Set<Listener> listeners = new HashSet<>();

    @Setter
    private volatile Consumer<LiveTextDelta> broadcaster = ignored -> {
    };

    public int subscriberCount() {
        synchronized (listeners) {
            return listeners.size();
        }
    }

    public Flux<LiveTextDelta> watch(ExecutionOwner owner, String id) {
        return Flux.<LiveTextDelta>create(sink -> {
                    var listener = new Listener(owner, id, sink);
                    synchronized (listeners) {
                        listeners.add(listener);
                    }
                    sink.onDispose(() -> {
                        synchronized (listeners) {
                            listeners.remove(listener);
                        }
                    });
                }, FluxSink.OverflowStrategy.ERROR)
                .onBackpressureBuffer(CAPACITY, ignored -> {
                }, BufferOverflowStrategy.DROP_OLDEST);
    }

    public void emit(LiveTextDelta delta) {
        receive(delta);
        broadcaster.accept(delta);
    }

    /**
     * Redis 回送也可能重复；不再次广播，浏览器通过 offset 去重。
     */
    public void receive(LiveTextDelta delta) {
        List<Listener> snapshot;
        synchronized (listeners) {
            snapshot = List.copyOf(listeners);
        }
        for (var listener : snapshot) {
            if (listener.owner().equals(delta.owner()) && listener.id().equals(delta.executionId()))
                listener.sink().next(delta);
        }
    }
}

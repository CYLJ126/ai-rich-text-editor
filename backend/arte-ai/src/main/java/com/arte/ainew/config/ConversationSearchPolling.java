package com.arte.ainew.config;

import com.arte.ainew.application.search.ConversationSearchSyncWorker.TickResult;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * 单个后台循环的有界调度状态；完整 C/T/I 轮次无变更且无到期积压才增加轮间等待。
 * 轮内继续快速推进；租约丢失或失败会丢弃跨轮观察，不把其他 Worker 的进度算作空闲证明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 10:05 ✾
 */
final class ConversationSearchPolling {
    private final long activeMs, maxMs;
    private final LongUnaryOperator jitter;
    private int completedLanes;
    private boolean dirty;
    private long idleMs;

    ConversationSearchPolling(NewAiSearchProperties.Sync config) {
        this(config, range -> ThreadLocalRandom.current().nextLong(range));
    }

    ConversationSearchPolling(NewAiSearchProperties.Sync config, LongUnaryOperator jitter) {
        activeMs = config.tick().toMillis();
        maxMs = config.idleMax().toMillis();
        this.jitter = jitter;
        reset();
    }

    void reset() {
        completedLanes = 0;
        dirty = false;
        idleMs = activeMs;
    }

    Duration next(TickResult tick) {
        if (!tick.acquired()) {
            reset();
            return Duration.ofMillis(activeMs + jitter.applyAsLong(Math.min(activeMs, maxMs - activeMs) + 1));
        }
        var polling = tick.polling();
        completedLanes |= polling.completedLanes();
        boolean active = tick.changed() > 0 || polling.failed() > 0;
        if (active) {
            idleMs = activeMs;
            dirty = true;
        }
        // 到期任务先排空，完整轮次的观察保留到队列排空后再结算。
        if (polling.nextWorkDelayMs() != null && polling.nextWorkDelayMs() == 0) return Duration.ofMillis(activeMs);
        long delay = activeMs;
        if (completedLanes == 7) {
            idleMs = dirty ? activeMs : Math.min(maxMs, idleMs * 2);
            completedLanes = 0;
            dirty = false;
            delay = idleMs;
        } else if (!active && (polling.nextScanDelayMs() == null || polling.nextScanDelayMs() > 0)) {
            // 所有已知扫描都未到期（例如仅校准／回填重试），无需每 250ms 检查。
            idleMs = Math.min(maxMs, idleMs * 2);
            delay = idleMs;
        }
        if (polling.nextWorkDelayMs() != null) delay = Math.min(delay, polling.nextWorkDelayMs());
        if (polling.nextScanDelayMs() != null && polling.nextScanDelayMs() > 0)
            delay = Math.min(delay, polling.nextScanDelayMs());
        return Duration.ofMillis(Math.max(1, delay));
    }
}

package com.arte.ainew.application.support;

import com.arte.ainew.common.execution.ExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 分段诊断，不写正文、凭据或供应商响应。elapsedMs 是当前组件流程的单调时钟耗时，
 * phaseMs 是调用方指定阶段的耗时；跨组件通过 invocationId 和日志时间对齐，不能相减不同流程的 elapsedMs。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:00 ✾
 */
@Slf4j
public record InvocationTiming(String invocationId, String traceId, String attemptId, long startedNanos) {

    public static InvocationTiming start(ExecutionContext context, String attemptId) {
        return new InvocationTiming(context.executionId(), context.traceId(), attemptId, System.nanoTime());
    }

    public InvocationTiming withAttempt(String id) {
        return new InvocationTiming(invocationId, traceId, id, startedNanos);
    }

    public void mark(String stage) {
        mark(stage, startedNanos, 0);
    }

    public void mark(String stage, long phaseStart, long sequence) {
        long now = System.nanoTime();
        log.info("AI_TIMING invocationId={} traceId={} attemptId={} stage={} elapsedMs={} phaseMs={} sequence={}",
                invocationId, traceId, attemptId, stage, millis(now - startedNanos), millis(now - phaseStart), sequence);
    }

    public void batch(String stage, long phaseStart, long sequence) {
        long now = System.nanoTime();
        log.debug("AI_TIMING invocationId={} traceId={} attemptId={} stage={} elapsedMs={} phaseMs={} sequence={}",
                invocationId, traceId, attemptId, stage, millis(now - startedNanos), millis(now - phaseStart), sequence);
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }
}

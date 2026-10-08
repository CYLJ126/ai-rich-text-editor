package com.arte.ainew.application.execution;

import java.io.Serial;
import java.time.Duration;

/**
 * 耐久 Outbox 重新排队；不是调用失败，不产生终态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 22:05 ✾
 */
public final class DispatchDeferredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -4210392303078027921L;

    private final Duration delay;

    public DispatchDeferredException(Duration delay) {
        super("DISPATCH_DEFERRED");
        this.delay = delay.isNegative() || delay.isZero() ? Duration.ofMillis(1) : delay;
    }

    public Duration delay() {
        return delay;
    }
}

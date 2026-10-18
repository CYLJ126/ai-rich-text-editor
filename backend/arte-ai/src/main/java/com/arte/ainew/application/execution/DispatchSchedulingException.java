package com.arte.ainew.application.execution;

import java.io.Serial;

/**
 * 安全调度拒绝，不携带原始参数、SQL 或节点凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
public final class DispatchSchedulingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 750045175128692496L;

    public enum Reason {QUEUE_FULL, NODE_NOT_READY, PLAN_REQUIRED, UNAVAILABLE}

    private final Reason reason;

    public DispatchSchedulingException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public DispatchSchedulingException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

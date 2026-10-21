package com.arte.core.diagnostics;

import java.io.Serial;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * 日志专用异常副本
 * <p>
 * 保留类型、栈及原因链，移除可能含正文、SQL 参数或凭据的异常消息。不得重抛此副本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 */
public final class SafeExceptionDiagnostics {

    private static final int MAX_NODES = 32;
    private static final int MAX_FRAMES = 128;
    private static final int MAX_SUPPRESSED = 8;

    private SafeExceptionDiagnostics() {
    }

    public static Throwable forLogging(Throwable failure) {
        return copy(failure, new IdentityHashMap<>());
    }

    private static Throwable copy(Throwable failure, IdentityHashMap<Throwable, Boolean> seen) {
        if (failure == null) return null;
        var result = new DiagnosticThrowable(failure.getClass().getName());
        if (seen.size() >= MAX_NODES || seen.put(failure, Boolean.TRUE) != null) {
            result.setStackTrace(new StackTraceElement[0]);
            return result;
        }
        var frames = failure.getStackTrace();
        result.setStackTrace(Arrays.copyOf(frames, Math.min(frames.length, MAX_FRAMES)));
        if (failure.getCause() != null) result.initCause(copy(failure.getCause(), seen));
        var suppressed = failure.getSuppressed();
        for (int i = 0; i < Math.min(suppressed.length, MAX_SUPPRESSED); i++) {
            if (seen.size() >= MAX_NODES) break;
            result.addSuppressed(copy(suppressed[i], seen));
        }
        return result;
    }

    private static final class DiagnosticThrowable extends Throwable {

        @Serial
        private static final long serialVersionUID = -1597220231232178772L;

        private final String originalType;

        private DiagnosticThrowable(String originalType) {
            super(originalType + " [message omitted]");
            this.originalType = originalType;
        }

        @Override
        public String toString() {
            return originalType + " [message omitted]";
        }
    }
}

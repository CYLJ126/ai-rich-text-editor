package com.arte.core.application.platform;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Platform wall-clock policy. Precision is preserved until the coordinated microsecond migration.
 * Observation times may move backwards; persisted metadata and completion times must not.
 * This does not establish distributed event order, uniqueness, or an authorization lifetime.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 11:55 ✾
 */
public final class PlatformTimes {
    private PlatformTimes() {
    }

    public static Instant observedNow(Clock clock) {
        return Objects.requireNonNull(clock, "clock").instant();
    }

    /**
     * Separate normalization point for metadata/receipts; currently retains existing precision.
     */
    public static Instant businessNow(Clock clock) {
        return observedNow(clock);
    }

    /**
     * Call with the previous value read under the resource's transaction governance lock.
     */
    public static Instant notBefore(Clock clock, Instant previous) {
        return notBefore(businessNow(clock), previous);
    }

    public static Instant notBefore(Instant observed, Instant previous) {
        Objects.requireNonNull(observed, "observed");
        return observed.isBefore(Objects.requireNonNull(previous, "previous")) ? previous : observed;
    }
}

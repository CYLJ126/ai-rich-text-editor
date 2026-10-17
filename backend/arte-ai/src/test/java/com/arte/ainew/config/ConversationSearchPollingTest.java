package com.arte.ainew.config;

import com.arte.ainew.application.search.ConversationSearchSyncWorker.Polling;
import com.arte.ainew.application.search.ConversationSearchSyncWorker.TickResult;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;

public class ConversationSearchPollingTest {
    private final NewAiSearchProperties.Sync config = new NewAiSearchProperties.Sync(null, null, null, null, null, null, null, null, null);

    private TickResult tick(int lanes, int changed, int written, int failed, Long work, Long scan) {
        return new TickResult(1, true, 50, 50, changed, written, new Polling(lanes, failed, work, scan));
    }

    private long cycle(ConversationSearchPolling policy) {
        assertEquals(250, policy.next(tick(1, 0, 0, 0, null, 0L)).toMillis());
        assertEquals(250, policy.next(tick(2, 0, 0, 0, null, 0L)).toMillis());
        return policy.next(tick(4, 0, 0, 0, null, 0L)).toMillis();
    }

    @Test
    public void emptyQueueAndUnchangedBatchesDoNotProveACompleteRound() {
        var policy = new ConversationSearchPolling(config);
        for (int i = 0; i < 30; i++) assertEquals(250, policy.next(tick(0, 0, 0, 0, null, 0L)).toMillis());
        assertEquals(500, cycle(policy));
    }

    @Test
    public void quietFullRoundsBackOffBetweenRoundsAndStayBounded() {
        var policy = new ConversationSearchPolling(config);
        for (long expected : new long[]{500, 1000, 2000, 4000, 5000, 5000}) assertEquals(expected, cycle(policy));
    }

    @Test
    public void dueBacklogMustDrainBeforeAQuietRoundCanBackOff() {
        var policy = new ConversationSearchPolling(config);
        assertEquals(250, policy.next(tick(7, 0, 0, 0, 0L, 0L)).toMillis());
        assertEquals(250, policy.next(tick(0, 0, 0, 0, 0L, 0L)).toMillis());
        assertEquals(500, policy.next(tick(0, 0, 0, 0, null, 0L)).toMillis());
    }

    @Test
    public void sourceChangesAndFailedExtractionResetBackoffAndInvalidateTheQuietRound() {
        for (boolean failure : new boolean[]{false, true}) {
            var policy = new ConversationSearchPolling(config);
            cycle(policy);
            cycle(policy);
            assertEquals(250, policy.next(tick(1, failure ? 0 : 1, 0, failure ? 1 : 0, null, 10000L)).toMillis());
            assertEquals(250, policy.next(tick(6, 0, 0, 0, null, 0L)).toMillis());
            assertEquals(500, cycle(policy));
        }
    }

    @Test
    public void forcedCalibrationWritesWithoutVersionChangesDoNotKeepIdleScanningHot() {
        var policy = new ConversationSearchPolling(config);
        assertEquals(500, policy.next(tick(7, 0, 20, 0, null, 0L)).toMillis());
    }

    @Test
    public void retryAndKnownScanDeadlinesBoundWaitingEvenBelowActiveTick() {
        var policy = new ConversationSearchPolling(config);
        assertEquals(73, policy.next(tick(7, 0, 0, 0, 73L, 0L)).toMillis());
        assertEquals(31, policy.next(tick(0, 0, 0, 0, null, 31L)).toMillis());
        assertEquals(250, policy.next(tick(0, 0, 0, 0, 0L, 0L)).toMillis());
    }

    @Test
    public void futureOnlyWorkCanSleepWithoutWaitingForAnotherScanRound() {
        var policy = new ConversationSearchPolling(config);
        assertEquals(500, policy.next(tick(0, 0, 0, 0, 10000L, null)).toMillis());
        assertEquals(1000, policy.next(tick(0, 0, 0, 0, 10000L, null)).toMillis());
        assertEquals(41, policy.next(tick(0, 0, 0, 0, 41L, null)).toMillis());
    }

    @Test
    public void skippedLeaseAddsBoundedJitterAndDiscardsPartialRoundObservation() {
        var policy = new ConversationSearchPolling(config, range -> range - 1);
        policy.next(tick(3, 0, 0, 0, null, 0L));
        assertEquals(500, policy.next(new TickResult(1, false, 0, 0, 0, 0, null)).toMillis());
        assertEquals(250, policy.next(tick(4, 0, 0, 0, null, 0L)).toMillis());
        policy.reset();
        assertEquals(500, cycle(policy));
    }

    @Test
    public void configuredIdleMaximumIsApplied() {
        var custom = new NewAiSearchProperties.Sync(null, null, null, null, null, null, null, null, null, null, Duration.ofMillis(300));
        assertEquals(300, cycle(new ConversationSearchPolling(custom)));
    }
}

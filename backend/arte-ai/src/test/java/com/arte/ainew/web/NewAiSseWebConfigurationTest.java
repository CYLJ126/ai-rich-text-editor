package com.arte.ainew.web;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class NewAiSseWebConfigurationTest {
    @Test
    public void slowWritesGrowToBoundBeforeRejectingRatherThanQueueingNewFirstText() throws Exception {
        var executor = new NewAiSseWebConfiguration().sseWriteExecutor();
        var started = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        executor.initialize();
        try {
            for (int i = 0; i < 16; i++)
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(org.springframework.core.task.TaskRejectedException.class, () -> executor.execute(() -> {
            }));
            assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}

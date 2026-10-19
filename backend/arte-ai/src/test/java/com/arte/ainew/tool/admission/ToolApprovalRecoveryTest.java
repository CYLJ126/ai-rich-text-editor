package com.arte.ainew.tool.admission;

import com.arte.ainew.application.tool.approval.ToolApprovalRecovery;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolApproval;
import com.arte.ainew.pojo.tool.ToolApprovalCommand;
import com.arte.ainew.pojo.tool.ToolApprovalReceipt;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

/**
 * 虚拟时钟验证恢复生命周期、失败后续扫及关闭开关，无真实等待或数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public class ToolApprovalRecoveryTest {

    @Test
    public void disabledRecoveryNeverReadsDatabase() {
        var timer = VirtualTimeScheduler.create();
        try {
            var calls = new AtomicInteger();
            var recovery = new ToolApprovalRecovery(store(calls), new NewAiToolApprovalProperties(false, null, 0), timer);
            recovery.start();
            timer.advanceTimeBy(Duration.ofHours(1));
            assertFalse(recovery.isRunning());
            assertEquals(0, calls.get());
        } finally {
            timer.dispose();
        }
    }

    @Test
    public void transientFailureDoesNotStopSerialRecoveryAndStopDisposesTimer() {
        var timer = VirtualTimeScheduler.create();
        try {
            var calls = new AtomicInteger();
            var recovery = new ToolApprovalRecovery(store(calls), new NewAiToolApprovalProperties(true, Duration.ofSeconds(1), 32), timer);
            recovery.start();
            assertTrue(recovery.isRunning());
            assertEquals(1, calls.get());
            recovery.start();
            assertEquals(1, calls.get());
            timer.advanceTimeBy(Duration.ofSeconds(2));
            assertEquals(3, calls.get());
            recovery.stop();
            timer.advanceTimeBy(Duration.ofHours(1));
            assertEquals(3, calls.get());
            assertFalse(recovery.isRunning());
        } finally {
            timer.dispose();
        }
    }

    private static ToolApprovalStore store(AtomicInteger calls) {
        return new ToolApprovalStore() {
            public Mono<ToolApproval> find(ExecutionOwner owner, String id) {
                return Mono.error(new AssertionError("No lookup"));
            }

            public Mono<StoreOutcome<ToolApprovalReceipt>> decide(ToolApprovalCommand command, ExecutionOwner actor,
                                                                  BiConsumer<ToolApproval, ToolExecutionSnapshot> authorization) {
                return Mono.error(new AssertionError("No decision"));
            }

            public Mono<Integer> expire(int limit) {
                return calls.incrementAndGet() == 1 ? Mono.error(new IllegalStateException("Injected transient failure")) : Mono.just(0);
            }
        };
    }
}

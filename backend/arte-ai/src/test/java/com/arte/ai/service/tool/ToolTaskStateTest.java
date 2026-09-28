package com.arte.ai.service.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.*;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ToolTaskStateTest {

    @Test
    public void shouldClaimRenewReportProgressAndCompleteWithVersions() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        ToolTask task = task(now);

        task.claim("worker-1", now.plusSeconds(60), now);
        assertEquals(ToolTaskHandle.Status.RUNNING, task.status());
        assertEquals(1, task.attempt());
        assertEquals(1, task.version());

        task.renewLease("worker-1", now.plusSeconds(90), now.plusSeconds(10));
        task.updateProgress(0.5, "half", now.plusSeconds(20));
        task.succeed(now.plusSeconds(30));

        assertTrue(task.terminal());
        assertEquals(ToolTaskHandle.Status.SUCCEEDED, task.status());
        assertEquals(Double.valueOf(1.0), task.progress());
        assertNull(task.workerId());
        assertNull(task.leaseUntil());
    }

    @Test
    public void shouldAllowExpiredLeaseToBeClaimedByAnotherWorker() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        ToolTask task = task(now);
        task.claim("worker-1", now.plusSeconds(1), now);
        task.claim("worker-2", now.plusSeconds(62), now.plusSeconds(2));
        assertEquals("worker-2", task.workerId());
        assertEquals(2, task.attempt());
    }

    @Test
    public void shouldSuspendForApprovalAndResumeWithoutExposingTokenInState() {
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        ToolTask task = task(now);

        task.queueForApproval("hashed-resume-token", now.plusSeconds(1));
        assertEquals(ToolTaskHandle.Status.WAITING_APPROVAL, task.status());
        assertEquals("hashed-resume-token", task.resumeToken());

        task.resume(now.plusSeconds(2));
        assertEquals(ToolTaskHandle.Status.QUEUED, task.status());
        assertNull(task.resumeToken());
    }

    @Test
    public void shouldPreserveExplicitNullArgumentsFromJsonSchemaInputs() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("optionalValue", null);

        DynamicToolRequest dynamicRequest = new DynamicToolRequest(arguments);
        RestToolInvokeRequest restRequest = new RestToolInvokeRequest(
                new ToolReference("article", "summary", "1.0.0"), arguments,
                null, null, "idem", ToolExecutionModeEnum.DEFERRED, Duration.ofMinutes(1), 0);
        ToolTask task = ToolTask.enqueue("task-null", "call-null", restRequest.tool(), arguments,
                new ToolExecutionPolicy(ToolExecutionModeEnum.DEFERRED, Duration.ofMinutes(1),
                        0, Duration.ofSeconds(1), 2048, false, false),
                "owner", "subject", "trace", "idem", null, Map.of(), Instant.now());

        assertTrue(dynamicRequest.arguments().containsKey("optionalValue"));
        assertNull(restRequest.arguments().get("optionalValue"));
        assertNull(task.arguments().get("optionalValue"));
    }

    private ToolTask task(Instant now) {
        return ToolTask.enqueue("task-1", "call-1",
                new ToolReference("article", "summary", "1.0.0"), Map.of("text", "hello"),
                new ToolExecutionPolicy(ToolExecutionModeEnum.DEFERRED, Duration.ofMinutes(1),
                        2, Duration.ofSeconds(1), 2048, false, false),
                "owner", "subject", "trace", "idem", null, Map.of(), now);
    }
}

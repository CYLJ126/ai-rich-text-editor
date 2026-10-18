package com.arte.ainew.tool.admission;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;

/**
 * 独立受理的意图与部署契约，独立于运行身份、当前选版及模型预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public class ToolAdmissionContractTest {

    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");

    @Test
    public void intentDigestCanonicalizesObjectOrderAndExactNumbers() {
        var a = new ToolAdmissionInput("binding", object("a", new StructuredValue.NumberValue(new BigDecimal("1.00")), "b", text("value")), null, null, null);
        var b = new ToolAdmissionInput("binding", object("b", text("value"), "a", new StructuredValue.NumberValue(new BigDecimal("1.0"))), null, null, null);
        assertEquals(ToolAdmissionDigests.submission(owner, a), ToolAdmissionDigests.submission(owner, b));
        assertNotEquals(ToolAdmissionDigests.submission(owner, a), ToolAdmissionDigests.submission(new ExecutionOwner("tenant", "workspace", "another"), a));
    }

    @Test
    public void requestedOptionsRemainPartOfIntentEvenWhenDefaultWouldMatch() {
        var inherited = new ToolAdmissionInput("binding", object(), null, null, null);
        for (var explicit : new ToolAdmissionInput[]{
                new ToolAdmissionInput("binding", object(), Duration.ofSeconds(5), null, null),
                new ToolAdmissionInput("binding", object(), null, 3, null),
                new ToolAdmissionInput("binding", object(), null, null, 4096L)})
            assertNotEquals(ToolAdmissionDigests.submission(owner, inherited), ToolAdmissionDigests.submission(owner, explicit));
    }

    @Test
    public void logicalCallIdentityIncludesOwnerBindingAndIdempotencyKeyWithoutDelimiterAmbiguity() {
        var id = ToolAdmissionDigests.callId(owner, "a", "bc");
        assertEquals(id, ToolAdmissionDigests.callId(owner, "a", "bc"));
        assertNotEquals(id, ToolAdmissionDigests.callId(owner, "ab", "c"));
        assertNotEquals(id, ToolAdmissionDigests.callId(new ExecutionOwner("tenant", "other", "subject"), "a", "bc"));
    }

    @Test
    public void malformedLimitsAreRejectedBeforeAnyStorage() {
        for (var duration : new Duration[]{Duration.ZERO, Duration.ofMillis(999), Duration.ofHours(2)})
            assertThrows(IllegalArgumentException.class, () -> new ToolAdmissionInput("binding", object(), duration, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolAdmissionInput("binding", object(), null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolAdmissionInput("binding", object(), null, 11, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolAdmissionInput("binding", object(), null, null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ToolAdmissionInput("binding", object(), null, null, 33L * 1024 * 1024));
    }

    @Test
    public void baselineAndIncrementalDdlDescribeExactlyTheSameSnapshotTable() throws Exception {
        var baseline = Files.readString(Path.of("scripts/arte-ai-new-ddl-mysql.sql"));
        var incremental = Files.readString(Path.of("scripts/arte-ai-tool-admission-migration.sql"));
        int start = baseline.indexOf("create table arte_ai_tool_execution_snapshot\n");
        int end = baseline.indexOf(';', start) + 1;
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(), baseline.substring(start, end).trim());
    }
}

package com.arte.ai.service.tool.security;

import com.arte.ai.pojo.tool.ToolApprovalDecision;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertThrows;

public class ToolApprovalDecisionTest {

    @Test
    public void shouldRequireReasonWhenRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ToolApprovalDecision(
                "request", false, "owner", " ", Instant.now()));
    }
}

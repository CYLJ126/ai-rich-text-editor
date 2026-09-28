package com.arte.ai.pojo.tool;

import org.junit.Test;

import static org.junit.Assert.*;

public class ApprovalDecisionRequestTest {

    @Test
    public void shouldValidateAndNormalizeReason() {
        assertNull(new ApprovalDecisionRequest(true, "  ").reason());
        assertEquals("reviewed", new ApprovalDecisionRequest(true, " reviewed ").reason());
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalDecisionRequest(false, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovalDecisionRequest(true, "x".repeat(1001)));
    }
}

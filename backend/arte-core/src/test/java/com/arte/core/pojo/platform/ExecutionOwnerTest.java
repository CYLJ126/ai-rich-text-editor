package com.arte.core.pojo.platform;

import com.arte.core.serialize.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionOwnerTest {

    @Test
    void ownerIsOnlyTheStableTenantWorkspaceSubjectTuple() {
        var owner = new ExecutionOwner("tenant", "workspace", "subject");
        assertEquals(new ExecutionOwner("tenant", "workspace", "subject"), owner);
        assertNotEquals(new ExecutionOwner("other", "workspace", "subject"), owner);
        assertNotEquals(new ExecutionOwner("tenant", "other", "subject"), owner);
        assertNotEquals(new ExecutionOwner("tenant", "workspace", "other"), owner);
        assertEquals(List.of("tenantId", "workspaceId", "subjectId"),
                Arrays.stream(ExecutionOwner.class.getRecordComponents()).map(component -> component.getName()).toList());
    }

    @Test
    void ownerKeepsLegacyIdentifierValidationWithoutNormalizingIds() {
        var owner = new ExecutionOwner(" tenant ", "workspace\u0001", "subject");
        assertEquals(" tenant ", owner.tenantId());
        assertEquals("workspace\u0001", owner.workspaceId());
        assertDoesNotThrow(() -> new ExecutionOwner("t".repeat(256), "w", "s"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOwner("", "w", "s"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOwner("t", null, "s"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOwner("t", "w", " "));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOwner("t", "w", "s".repeat(257)));
    }

    @Test
    void publicOwnerKeepsTheFlatJsonShape() {
        assertEquals("{\"subjectId\":\"subject\",\"tenantId\":\"tenant\",\"workspaceId\":\"workspace\"}",
                CanonicalJson.encode(new ExecutionOwner("tenant", "workspace", "subject")));
    }
}

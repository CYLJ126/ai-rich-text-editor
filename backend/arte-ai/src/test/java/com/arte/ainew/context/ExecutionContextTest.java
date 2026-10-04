package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ExecutionContextTest {
    @Test
    public void scopesAreCopiedAndImmutable() {
        var input = new HashSet<>(Set.of("read"));
        var authorization = new ExecutionAuthorization(
                new ExecutionPrincipal("1", "alice", ExecutionPrincipal.Kind.USER), "tenant", "workspace", input, "grant");
        input.add("write");
        assertEquals(Set.of("read"), authorization.scopes());
        assertThrows(UnsupportedOperationException.class, () -> authorization.scopes().add("write"));
    }

    @Test
    public void childPreservesIdentityAndCannotExtendAuthorityOrDeadline() {
        var parent = ExecutionContexts.context("parent", "1", "alice", Instant.parse("2030-01-01T00:00:00Z"));
        var child = parent.child("child", parent.deadline().minusSeconds(1), Set.of("read"), "child-key");
        assertEquals("parent", child.parentExecutionId());
        assertEquals(parent.traceId(), child.traceId());
        assertEquals(parent.budgetRef(), child.budgetRef());
        assertEquals(parent.authorization().principal(), child.authorization().principal());
        assertEquals(Set.of("read"), child.authorization().scopes());
        assertThrows(IllegalArgumentException.class,
                () -> parent.child("child", parent.deadline().plusSeconds(1), Set.of("read"), null));
        assertThrows(IllegalArgumentException.class,
                () -> parent.child("child", parent.deadline(), Set.of("write"), null));
        assertThrows(IllegalArgumentException.class,
                () -> parent.child("parent", parent.deadline(), Set.of("read"), null));
    }

    @Test
    public void durableContextRoundTripsWithoutRuntimeObjects() throws Exception {
        var original = ExecutionContexts.context("execution", "1", "alice", Instant.parse("2030-01-01T00:00:00Z"));
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(original, (ExecutionContext) input.readObject());
        }
        assertFalse(java.io.Serializable.class.isAssignableFrom(ExecutionRuntimeContext.class));
    }
}

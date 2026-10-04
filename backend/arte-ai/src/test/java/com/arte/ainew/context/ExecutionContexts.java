package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;

import java.time.Instant;
import java.util.Set;

final class ExecutionContexts {
    static ExecutionContext context(String id, String userId, String name, Instant deadline) {
        return new ExecutionContext(id, "trace-" + id,
                new ExecutionAuthorization(new ExecutionPrincipal(userId, name, ExecutionPrincipal.Kind.USER),
                        "tenant-1", "workspace-1", Set.of("read", "generate"), "grant-1"),
                deadline, null, "budget-1", "release-1", "key-" + id);
    }
}

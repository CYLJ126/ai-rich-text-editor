package com.arte.base.model.admission;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.validation.ContractChecks;

import java.time.Duration;

public record AdmissionRequest(ExecutionContext context, AdmissionKey key, AdmissionPriority priority,
                               Duration maxWait) {
    public AdmissionRequest {
        context = ContractChecks.required(context, "context");
        key = ContractChecks.required(key, "key");
        priority = ContractChecks.required(priority, "priority");
        maxWait = ContractChecks.required(maxWait, "maxWait");
        if (!key.tenantId().equals(context.scope().tenantId()))
            throw new IllegalArgumentException("admission tenant mismatch");
        if (maxWait.isNegative()) throw new IllegalArgumentException("maxWait must not be negative");
    }
}

package com.arte.base.model.observability;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标识、引用、原因码和策略版本组成的审计事实；不提供正文／凭据／自由错误文案字段。
 */
public record AuditRecord(String eventId, String eventType, ExecutionScope scope, PrincipalRef executor,
                          String traceId, String executionId, Instant occurredAt, AuditOutcome outcome,
                          List<ResourceRef> resources, Set<String> reasonCodes, Map<String, String> policyVersions) {
    public AuditRecord {
        eventId = ContractChecks.identifier(eventId, "eventId");
        eventType = ContractChecks.identifier(eventType, "eventType");
        scope = ContractChecks.required(scope, "scope");
        executor = ContractChecks.required(executor, "executor");
        traceId = ContractChecks.identifier(traceId, "traceId");
        executionId = ContractChecks.optionalIdentifier(executionId, "executionId");
        occurredAt = ContractChecks.required(occurredAt, "occurredAt");
        outcome = ContractChecks.required(outcome, "outcome");
        ContractChecks.required(resources, "resources").forEach(ref -> ContractChecks.required(ref, "resource"));
        resources = List.copyOf(resources);
        reasonCodes = ContractChecks.identifiers(reasonCodes, "reasonCodes");
        ContractChecks.required(policyVersions, "policyVersions").forEach((id, version) -> {
            ContractChecks.identifier(id, "policy id");
            ContractChecks.identifier(version, "policy version");
        });
        policyVersions = Map.copyOf(policyVersions);
    }
}

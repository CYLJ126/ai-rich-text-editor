package com.arte.base.model.security;

import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 一次策略判定的不可变快照，不是可独立使用的授权凭证。
 * reasonCodes 为稳定原因标识，不承载敏感内容或自由文案。
 * policyVersions 是提供者实际检查的策略 ID／版本副本，不能代替实时版本检查。
 * ALLOW 必须有策略版本及有限有效期；拒绝或无法确定必须有原因。
 */
public record PolicyDecision(
        PolicyOutcome outcome,
        Set<String> reasonCodes,
        Map<String, String> policyVersions,
        Instant evaluatedAt,
        Instant validUntil
) {

    public PolicyDecision {
        outcome = ContractChecks.required(outcome, "outcome");
        reasonCodes = ContractChecks.identifiers(reasonCodes, "reasonCodes");
        ContractChecks.required(policyVersions, "policyVersions");
        policyVersions.forEach((id, version) -> {
            ContractChecks.identifier(id, "policyVersions key");
            ContractChecks.identifier(version, "policyVersions value");
        });
        policyVersions = Map.copyOf(policyVersions);
        evaluatedAt = ContractChecks.required(evaluatedAt, "evaluatedAt");
        if (validUntil != null && !validUntil.isAfter(evaluatedAt)) {
            throw new IllegalArgumentException("validUntil must be after evaluatedAt");
        }
        if (outcome == PolicyOutcome.ALLOW && (policyVersions.isEmpty() || validUntil == null)) {
            throw new IllegalArgumentException("ALLOW requires policyVersions and validUntil");
        }
        if (outcome != PolicyOutcome.ALLOW && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("DENY and INDETERMINATE require reasonCodes");
        }
    }

    public static PolicyDecision allow(Map<String, String> policyVersions, Instant evaluatedAt, Instant validUntil) {
        return new PolicyDecision(PolicyOutcome.ALLOW, Set.of(), policyVersions, evaluatedAt, validUntil);
    }

    public static PolicyDecision deny(Set<String> reasonCodes, Map<String, String> policyVersions, Instant evaluatedAt) {
        return new PolicyDecision(PolicyOutcome.DENY, reasonCodes, policyVersions, evaluatedAt, null);
    }

    public static PolicyDecision indeterminate(Set<String> reasonCodes, Instant evaluatedAt) {
        return new PolicyDecision(PolicyOutcome.INDETERMINATE, reasonCodes, Map.of(), evaluatedAt, null);
    }

    /**
     * 仅判断本快照的时间窗口；权限撤销仍需在操作边界重新评估当前策略。
     */
    public boolean isAllowedAt(Instant now) {
        ContractChecks.required(now, "now");
        return outcome == PolicyOutcome.ALLOW && !now.isBefore(evaluatedAt) && now.isBefore(validUntil);
    }
}

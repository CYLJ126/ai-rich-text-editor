package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 服务端发布预检结果
 * <p>
 * 只含稳定问题码，不保存异常正文或实际失败值。
 *
 * @param contractDigest    通过检查的固定契约摘要，失败时为 null。
 * @param discoveryChecksum 检查所见完整节点清单摘要，失败时为 null。
 * @param issues            问题码；空集合才表示通过。
 * @param checkedAt         服务端检查时间，不能由外部请求提供。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public record ToolPublicationValidation(String contractDigest, String discoveryChecksum, List<Issue> issues,
                                        Instant checkedAt) {

    public enum Issue {
        DISCOVERY_UNHEALTHY, NOT_DISCOVERED, IMPLEMENTATION_MISMATCH, SCHEMA_INVALID,
        EXAMPLE_INVALID, RISK_UNREVIEWED, IDEMPOTENCY_UNVERIFIED, POLICY_TOO_WEAK, UNSUPPORTED_CAPABILITY
    }

    public ToolPublicationValidation {
        issues = ContractChecks.list(issues, "issues", 0, Issue.values().length);
        ContractChecks.unique(issues, "issues");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (issues.isEmpty()) {
            ContractChecks.digest(contractDigest, "contractDigest");
            ContractChecks.digest(discoveryChecksum, "discoveryChecksum");
        } else
            ContractChecks.require(contractDigest == null && discoveryChecksum == null, "Failed validation cannot attest a contract");
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}

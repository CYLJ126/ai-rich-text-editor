package com.arte.ainew.config;

import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 安装审批表并统一升级节点后显式开启恢复；开关不开放任何工具或审批 HTTP 权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-approval", ignoreUnknownFields = false)
public record NewAiToolApprovalProperties(boolean enabled, Duration pollInterval, int batchSize,
                                          List<Approvers> approvers) {

    public NewAiToolApprovalProperties(boolean enabled, Duration pollInterval, int batchSize) {
        this(enabled, pollInterval, batchSize, List.of());
    }

    /**
     * 受信部署配置，按完整租户／空间指定稳定主体，不从浏览器接收。
     */
    public record Approvers(String tenantId, String workspaceId, Set<String> subjectIds) {
        public Approvers {
            ContractChecks.id(tenantId, "tenantId");
            ContractChecks.id(workspaceId, "workspaceId");
            subjectIds = ContractChecks.set(subjectIds, "approval subjects", 32);
            subjectIds.forEach(id -> ContractChecks.id(id, "approval subject"));
        }
    }

    @ConstructorBinding
    public NewAiToolApprovalProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        ContractChecks.require(pollInterval.compareTo(Duration.ofMillis(100)) >= 0
                && pollInterval.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid approval recovery interval");
        batchSize = batchSize == 0 ? 64 : batchSize;
        ContractChecks.range(batchSize, "approval recovery batch", 1, 256);
        approvers = approvers == null ? List.of() : ContractChecks.list(approvers, "approval scopes", 0, 256);
        ContractChecks.unique(approvers.stream().map(a -> List.of(a.tenantId(), a.workspaceId())).toList(), "approval scopes");
    }
}

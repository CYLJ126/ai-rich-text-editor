package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

import java.io.Serializable;
import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * 工具的受信风险画像；业务副作用与费用、网络外发分别判断。
 * 只读工具仍可能外发数据，幂等声明也不能证明超时后可直接重做。
 *
 * @param sideEffect            操作类别，复用通用能力分类，不维护另一份只读／写入布尔状态。
 * @param destructive           是否破坏性操作；与 READ_ONLY 互斥。
 * @param idempotency           幂等依据；实际领域或远端防重仍须在发布和执行时验证。
 * @param requiredScopes        声明的必要权限集合，不是当前主体已获得的权限。
 * @param allowedNetworkOrigins 允许目标的 HTTP(S) origin 声明；不含业务路径、凭据、查询或片段。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public record ToolRiskProfile(CapabilityDescriptor.SideEffect sideEffect, boolean destructive,
                              Idempotency idempotency, Set<String> requiredScopes,
                              Set<URI> allowedNetworkOrigins) implements Serializable {
    public enum Idempotency {UNVERIFIED, NONE, DOMAIN_OPERATION_KEY, REMOTE_OPERATION_KEY}

    public ToolRiskProfile {
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(idempotency, "idempotency");
        ContractChecks.require(!destructive || sideEffect != CapabilityDescriptor.SideEffect.READ_ONLY,
                "Read-only tool cannot be destructive");
        requiredScopes = ContractChecks.set(requiredScopes, "requiredScopes", ContractChecks.MAX_ITEMS);
        requiredScopes.forEach(scope -> ContractChecks.id(scope, "required scope"));
        allowedNetworkOrigins = ContractChecks.set(allowedNetworkOrigins, "allowedNetworkOrigins", ContractChecks.MAX_ITEMS);
        allowedNetworkOrigins.forEach(ToolRiskProfile::validateOrigin);
    }

    /**
     * 结构层最低人工要求；重要外部副作用等更严格规则由发布策略补充。
     * UNKNOWN 即使满足本方法也不表示可执行，仍须完成风险审核与能力发布。
     */
    public ToolExecutionPolicy.ApprovalMode minimumApprovalMode() {
        if (destructive || sideEffect == CapabilityDescriptor.SideEffect.UNKNOWN) {
            return ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL;
        }
        return sideEffect == CapabilityDescriptor.SideEffect.READ_ONLY
                ? ToolExecutionPolicy.ApprovalMode.NONE
                : ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION;
    }

    /**
     * 仅校验声明结构；DNS、实际连接地址、端口策略和当前外发授权留在发送边界检查。
     */
    private static void validateOrigin(URI origin) {
        ContractChecks.text(origin.toString(), "allowedNetworkOrigin", 2048);
        ContractChecks.require(("https".equalsIgnoreCase(origin.getScheme()) || "http".equalsIgnoreCase(origin.getScheme()))
                        && origin.getHost() != null && origin.getUserInfo() == null
                        && origin.getRawQuery() == null && origin.getRawFragment() == null
                        && (origin.getRawPath() == null || origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))
                        && (origin.getPort() == -1 || origin.getPort() >= 1 && origin.getPort() <= 65535),
                "Tool network origin must be HTTP(S) without credentials, business path, query or fragment");
    }
}

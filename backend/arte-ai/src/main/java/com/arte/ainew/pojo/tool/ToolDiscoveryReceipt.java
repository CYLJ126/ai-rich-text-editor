package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.pojo.platform.Scope;

import java.time.Instant;
import java.util.Objects;

/**
 * 本地完整目录刷新的原始管理审计；成功只证明登记／节点观察，不证明发布或集群就绪。
 *
 * @param commandId      真实主体类别和幂等键的固定摘要。
 * @param actorSubjectId 实际稳定主体。
 * @param actorKind      实际主体类别。
 * @param scope          验证管理资格的真实空间，目录事实本身仍为平台受管。
 * @param idempotencyKey 原始请求头命令键。
 * @param reason         原始审核原因，不写日志。
 * @param result         目录提交或来源失败的稳定决定；BUSY／数据库故障不保存假回执。
 * @param checksum       成功完整候选的摘要，失败为空。
 * @param providers      成功候选提供者数量。
 * @param schemas        成功候选 Schema 数量。
 * @param tools          成功候选工具数量。
 * @param recordedAt     首次完成的 UTC 微秒时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public record ToolDiscoveryReceipt(String commandId, String actorSubjectId, PrincipalKindEnum actorKind, Scope scope,
                                   String idempotencyKey, String reason, Code result, String checksum,
                                   int providers, int schemas, int tools, Instant recordedAt) {

    public enum Code {APPLIED, REPLAYED, SOURCE_FAILED, SOURCE_EMPTY, SOURCE_TIMEOUT, INVALID_DEFINITION, ADAPTER_MISMATCH, CONTRACT_DRIFT, STORE_REJECTED}

    public ToolDiscoveryReceipt {
        ContractChecks.digest(commandId, "commandId");
        ContractChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(actorKind);
        Objects.requireNonNull(scope);
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        ContractChecks.text(reason, "reason", 1024);
        Objects.requireNonNull(result);
        Objects.requireNonNull(recordedAt);
        if (result == Code.APPLIED || result == Code.REPLAYED) ContractChecks.digest(checksum, "checksum");
        else
            ContractChecks.require(checksum == null && providers == 0 && schemas == 0 && tools == 0, "Failed discovery cannot attest a catalog");
        ContractChecks.range(providers, "providers", 0, 32);
        ContractChecks.range(schemas, "schemas", 0, 128);
        ContractChecks.range(tools, "tools", 0, 128);
    }

    public boolean successful() {
        return result == Code.APPLIED || result == Code.REPLAYED;
    }
}

package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 按稳定租户 ID 升序扫描的候选，不包含授权决定。
 *
 * @param records 按稳定租户 ID 升序排列的候选 ID 列表
 * @param exhausted 是否已扫描到候选尾部
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public record TenantCandidatePage(List<String> records, boolean exhausted) {

    public TenantCandidatePage {
        records = List.copyOf(records);
        PlatformChecks.require(records.size() <= 100 && (exhausted || !records.isEmpty()), "invalid tenant candidate page");
        records.forEach(id -> PlatformChecks.id(id, "candidate tenantId"));
    }
}

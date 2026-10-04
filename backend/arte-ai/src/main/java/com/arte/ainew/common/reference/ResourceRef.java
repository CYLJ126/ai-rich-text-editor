package com.arte.ainew.common.reference;

import com.arte.ainew.common.validation.ContractChecks;
import java.io.Serializable;

/**
 * 资源固定版本或草稿引用，范围由资源领域解释；同时有 version 和 draftId 时 version 为草稿基准版本。
 * 草稿必须有实际内容摘要；引用不携带全文或下载地址，不授予读取及外发权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record ResourceRef(String resourceType, String resourceId, String version,
                          String draftId, String rangeRef, String contentDigest) implements Serializable {
    public ResourceRef {
        ContractChecks.id(resourceType, "resourceType");
        ContractChecks.id(resourceId, "resourceId");
        ContractChecks.optionalId(version, "version");
        ContractChecks.optionalId(draftId, "draftId");
        ContractChecks.optionalId(rangeRef, "rangeRef");
        ContractChecks.require(version != null || draftId != null, "Resource version or draft identity is required");
        if (version != null) {
            ContractChecks.require(!version.equalsIgnoreCase("latest"), "Resource version must be fixed");
        }
        if (contentDigest != null || draftId != null) {
            ContractChecks.digest(contentDigest, "contentDigest");
        }
    }
}

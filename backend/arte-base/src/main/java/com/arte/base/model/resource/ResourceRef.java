package com.arte.base.model.resource;

import com.arte.base.validation.ContractChecks;

/**
 * 通用资源引用。resourceType、resourceId 必填，其余字段缺省使用 null，不能用空字符串。
 * version 为不透明正式版本；有 draftId 时，version 可表达草稿基准版本，新草稿可没有基准版本。
 * 草稿必须携带内容摘要；范围引用必须绑定版本或草稿，范围结构及摘要算法由资源领域解释。
 * 引用不授予访问权限，不验证资源存在性。
 */
public record ResourceRef(
        String resourceType,
        String resourceId,
        String version,
        String draftId,
        String rangeRef,
        String contentDigest
) {

    public ResourceRef {
        resourceType = ContractChecks.identifier(resourceType, "resourceType");
        resourceId = ContractChecks.identifier(resourceId, "resourceId");
        version = ContractChecks.optionalIdentifier(version, "version");
        draftId = ContractChecks.optionalIdentifier(draftId, "draftId");
        rangeRef = ContractChecks.optionalIdentifier(rangeRef, "rangeRef");
        contentDigest = ContractChecks.optionalIdentifier(contentDigest, "contentDigest");
        if (draftId != null && contentDigest == null) {
            throw new IllegalArgumentException("draftId requires contentDigest");
        }
        if (rangeRef != null && version == null && draftId == null) {
            throw new IllegalArgumentException("rangeRef requires version or draftId");
        }
    }

    /**
     * 读取当前资源，不作为可重放上下文或固定来源。
     */
    public static ResourceRef current(String resourceType, String resourceId) {
        return new ResourceRef(resourceType, resourceId, null, null, null, null);
    }

    public static ResourceRef saved(String resourceType, String resourceId, String version) {
        ContractChecks.identifier(version, "version");
        return new ResourceRef(resourceType, resourceId, version, null, null, null);
    }

    public static ResourceRef draft(String resourceType, String resourceId, String baseVersion,
                                    String draftId, String contentDigest) {
        ContractChecks.identifier(draftId, "draftId");
        return new ResourceRef(resourceType, resourceId, baseVersion, draftId, null, contentDigest);
    }

    public ResourceRef withRange(String rangeRef) {
        return new ResourceRef(resourceType, resourceId, version, draftId, rangeRef, contentDigest);
    }

    public boolean isDraft() {
        return draftId != null;
    }

    /**
     * 结构上指明版本或草稿；具体版本不可变性及草稿摘要仍须由资源提供者校验。
     */
    public boolean isPinned() {
        return version != null || draftId != null;
    }
}

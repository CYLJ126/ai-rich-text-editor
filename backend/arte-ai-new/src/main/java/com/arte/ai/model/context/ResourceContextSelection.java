package com.arte.ai.model.context;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

/** 显式固定的资料选择；rangeRef 使用领域声明的坐标，draftText 仅用于带摘要的草稿。 */
public record ResourceContextSelection(ResourceRef resource, String draftText) {
    public ResourceContextSelection {
        resource = ContractChecks.required(resource, "resource");
        if (!resource.isPinned() || resource.version() == null || resource.contentDigest() == null)
            throw new IllegalArgumentException("resource requires a base version and whole-content digest");
        if (resource.isDraft() != (draftText != null))
            throw new IllegalArgumentException("draft text must agree with draft resource");
        if (draftText != null && draftText.length() > 1048576)
            throw new IllegalArgumentException("draft text exceeds limit");
    }
}

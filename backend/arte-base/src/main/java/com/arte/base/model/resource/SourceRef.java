package com.arte.base.model.resource;

import com.arte.base.validation.ContractChecks;

/**
 * 来源必须固定到正式版本或带摘要的草稿，不能引用未固定的“当前内容”。
 * citationId 可为空，由上下文组装时分配；重用、展示及读取来源均须重新授权。
 */
public record SourceRef(ResourceRef resource, String citationId) {

    public SourceRef {
        resource = ContractChecks.required(resource, "resource");
        citationId = ContractChecks.optionalIdentifier(citationId, "citationId");
        if (!resource.isPinned()) {
            throw new IllegalArgumentException("source resource requires version or draftId");
        }
    }
}

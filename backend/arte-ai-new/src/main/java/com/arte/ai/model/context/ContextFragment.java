package com.arte.ai.model.context;

import com.arte.base.model.resource.SourceRef;
import com.arte.base.validation.ContractChecks;

/**
 * 实际资料片段及固定来源；裁剪时说明使用范围，不把派生片段冒充完整原文。
 */
public record ContextFragment(String citationId, SourceRef source, String content,
                              boolean truncated, String coverageDescription) {
    public ContextFragment {
        citationId = ContractChecks.identifier(citationId, "citationId");
        source = ContractChecks.required(source, "source");
        content = ContractChecks.required(content, "content");
        if (source.citationId() != null && !source.citationId().equals(citationId)) {
            throw new IllegalArgumentException("citationId must agree with source");
        }
        if (truncated && (coverageDescription == null || coverageDescription.isBlank())) {
            throw new IllegalArgumentException("truncated content requires a coverage description");
        }
    }
}

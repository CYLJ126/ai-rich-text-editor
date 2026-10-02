package com.arte.ai.model.definition;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

/**
 * 固定正式配置版本，不接受 latest 或草稿作为可执行引用。
 */
public record DefinitionRef(String definitionType, String definitionId, String version) {
    public DefinitionRef {
        definitionType = ContractChecks.identifier(definitionType, "definitionType");
        definitionId = ContractChecks.identifier(definitionId, "definitionId");
        version = ContractChecks.identifier(version, "version");
        if ("latest".equalsIgnoreCase(version)) throw new IllegalArgumentException("version must be pinned");
    }

    public ResourceRef resource() {
        return ResourceRef.saved(definitionType, definitionId, version);
    }
}

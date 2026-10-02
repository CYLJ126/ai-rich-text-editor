package com.arte.ai.api.control;

import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.spi.store.ModelDefinitionStore;
import com.arte.base.validation.ContractChecks;

import java.util.Optional;

/**
 * 发布配置查询入口；管理／发布命令后续按场景补充。
 */
public class CapabilityCatalog {
    private final ModelDefinitionStore store;

    public CapabilityCatalog(ModelDefinitionStore store) {
        this.store = ContractChecks.required(store, "store");
    }

    public Optional<CapabilityDefinition> find(DefinitionRef ref) {
        return store.capability(ref); }
}

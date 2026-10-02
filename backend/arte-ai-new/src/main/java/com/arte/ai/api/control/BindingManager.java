package com.arte.ai.api.control;

import com.arte.ai.model.definition.BindingDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.spi.store.ModelDefinitionStore;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.util.Optional;

/**
 * 发布配置查询入口；管理／发布命令后续按场景补充。
 */
public class BindingManager {
    private final ModelDefinitionStore store;

    public BindingManager(ModelDefinitionStore store) {
        this.store = ContractChecks.required(store, "store");
    }

    public Optional<BindingDefinition> find(ExecutionScope scope, DefinitionRef ref) {
        return store.binding(scope, ref); }
}

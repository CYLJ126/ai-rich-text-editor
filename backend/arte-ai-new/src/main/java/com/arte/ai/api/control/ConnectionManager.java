package com.arte.ai.api.control;

import com.arte.ai.model.definition.*;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.ai.spi.store.ModelDefinitionStore;
import com.arte.base.validation.ContractChecks;

import java.util.Optional;

/**
 * 发布配置查询入口；管理／发布命令后续按场景补充。
 */
public class ConnectionManager {
    private final ModelDefinitionStore store;

    public ConnectionManager(ModelDefinitionStore store) {
        this.store = ContractChecks.required(store, "store");
    }

    public Optional<ConnectionDefinition> find(DefinitionRef ref) {
        return store.connection(ref); }
}

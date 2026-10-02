package com.arte.ai.spi.store;

import com.arte.ai.model.definition.BindingDefinition;
import com.arte.ai.model.definition.CapabilityDefinition;
import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.identity.ExecutionScope;

import java.util.Optional;

/**
 * 当前发布配置查询；每次解析均检查当前启用状态，不能只持有历史发布快照。
 */
public interface ModelDefinitionStore {
    Optional<CapabilityDefinition> capability(DefinitionRef ref);

    Optional<ConnectionDefinition> connection(DefinitionRef ref);

    Optional<BindingDefinition> binding(ExecutionScope scope, DefinitionRef ref);
}

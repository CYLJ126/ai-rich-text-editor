package com.arte.app.platform.persistence;

import com.arte.ainew.pojo.model.ModelSelection;
import com.arte.ainew.spi.model.ModelSelectionSource;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隔离执行回归显式提供的空间选择事实；不替代 M3 持久化或业务启用命令验收。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 14:20 ✾
 */
final class TestModelSelections implements ModelSelectionSource {

    private final Map<Scope, Map<String, ModelSelection>> scopes = new ConcurrentHashMap<>();

    void enable(Scope scope, String resource, DefinitionRef binding) {
        scopes.computeIfAbsent(scope, key -> new ConcurrentHashMap<>()).put(resource, new ModelSelection(resource, binding));
    }

    @Override
    public List<ModelSelection> list(Scope scope, boolean current) {
        return List.copyOf(scopes.getOrDefault(scope, Map.of()).values());
    }
}

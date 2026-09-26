package com.arte.ai.service.tool;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolAvailabilityService;
import com.arte.ai.api.tool.ToolDefinitionValidator;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.common.exception.DuplicateToolException;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolQuery;
import com.arte.ai.pojo.tool.ToolReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于内存索引的默认工具注册表
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Service
@RequiredArgsConstructor
public class DefaultToolRegistry implements ToolRegistry {

    private static final Comparator<ToolDefinition> DEFINITION_ORDER = Comparator
            .comparing((ToolDefinition definition) -> definition.reference().namespace())
            .thenComparing(definition -> definition.reference().name())
            .thenComparing(definition -> definition.reference().version());

    private final ToolDefinitionValidator definitionValidator;
    private final ToolAvailabilityService availabilityService;
    private final Map<ToolReference, RegistryEntry> tools = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void register(Tool<?, ?> tool) {
        register("unknown", tool);
    }

    @Override
    public void register(String providerId, Tool<?, ?> tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        ToolDefinition definition = tool.getDefinition();
        definitionValidator.validate(definition);
        ToolReference reference = definition.reference();
        if (!availabilityService.isAvailable(reference)) {
            throw new IllegalStateException("tool is not published or its provider is disabled: " + reference);
        }

        RegistryEntry entry = new RegistryEntry(providerId, tool);
        RegistryEntry existing = tools.putIfAbsent(reference, entry);
        if (existing != null && existing.tool() != tool) {
            throw new DuplicateToolException(reference);
        }
        if (existing == null) {
            notifyChanged(providerId);
        }
    }

    @Override
    public void unregister(ToolReference reference) {
        if (reference == null) {
            return;
        }
        RegistryEntry removed = tools.remove(reference);
        if (removed != null) {
            notifyChanged(removed.providerId());
        }
    }

    @Override
    public void unregisterProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        boolean changed = tools.entrySet().removeIf(entry -> providerId.equals(entry.getValue().providerId()));
        if (changed) {
            notifyChanged(providerId);
        }
    }

    @Override
    public Optional<Tool<?, ?>> resolve(ToolReference reference) {
        if (reference == null || !availabilityService.isAvailable(reference)) {
            return Optional.empty();
        }
        RegistryEntry entry = tools.get(reference);
        return entry == null ? Optional.empty() : Optional.of(entry.tool());
    }

    @Override
    public List<ToolDefinition> search(ToolQuery query) {
        ToolQuery criteria = query == null
                ? new ToolQuery(null, null, Set.of(), null, false)
                : query;
        List<ToolDefinition> result = new ArrayList<>();
        for (RegistryEntry entry : tools.values()) {
            ToolDefinition definition = entry.tool().getDefinition();
            if (availabilityService.isAvailable(definition.reference()) && matches(definition, criteria)) {
                result.add(definition);
            }
        }
        result.sort(DEFINITION_ORDER);
        return List.copyOf(result);
    }

    @Override
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private boolean matches(ToolDefinition definition, ToolQuery query) {
        if (query.namespace() != null && !query.namespace().isBlank()
                && !query.namespace().equals(definition.reference().namespace())) {
            return false;
        }
        if (!query.includeDeprecated() && definition.deprecated()) {
            return false;
        }
        if (query.maximumRiskLevel() != null
                && definition.riskProfile().level().ordinal() > query.maximumRiskLevel().ordinal()) {
            return false;
        }
        if (!query.tags().isEmpty() && !definition.tags().containsAll(query.tags())) {
            return false;
        }
        if (query.keyword() == null || query.keyword().isBlank()) {
            return true;
        }
        String keyword = query.keyword().trim().toLowerCase(Locale.ROOT);
        return contains(definition.reference().name(), keyword)
                || contains(definition.title(), keyword)
                || contains(definition.description(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private void notifyChanged(String providerId) {
        listeners.forEach(listener -> listener.onToolListChanged(providerId));
    }

    private record RegistryEntry(String providerId, Tool<?, ?> tool) {
    }
}

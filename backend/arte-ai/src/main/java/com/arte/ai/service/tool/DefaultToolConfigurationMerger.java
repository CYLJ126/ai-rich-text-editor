package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolConfigurationMerger;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Map 型工具配置的递归覆盖合并器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Service
public class DefaultToolConfigurationMerger implements ToolConfigurationMerger {

    @Override
    public Map<String, Object> merge(Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> result = deepCopy(defaults);
        mergeInto(result, overrides);
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private void mergeInto(Map<String, Object> target, Map<String, Object> overrides) {
        if (overrides == null) {
            return;
        }
        overrides.forEach((key, value) -> {
            Object current = target.get(key);
            if (current instanceof Map<?, ?> currentMap && value instanceof Map<?, ?> overrideMap) {
                Map<String, Object> nested = deepCopy((Map<String, Object>) currentMap);
                mergeInto(nested, (Map<String, Object>) overrideMap);
                target.put(key, Map.copyOf(nested));
            } else {
                target.put(key, deepCopyValue(value));
            }
        });
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> result.put(key, deepCopyValue(value)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Map.copyOf(deepCopy((Map<String, Object>) map));
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(this::deepCopyValue).toList();
        }
        return value;
    }
}

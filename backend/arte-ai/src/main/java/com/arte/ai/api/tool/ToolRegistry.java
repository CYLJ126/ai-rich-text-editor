package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolQuery;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.List;
import java.util.Optional;

/**
 * 工具注册表，负责汇总各 {@link ToolProvider} 提供的工具并按精确版本解析。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolRegistry {

    void register(Tool<?, ?> tool);

    void unregister(ToolReference reference);

    Optional<Tool<?, ?>> resolve(ToolReference reference);

    List<ToolDefinition> search(ToolQuery query);

    void addListener(Listener listener);

    void removeListener(Listener listener);

    interface Listener {

        void onToolListChanged(String providerId);
    }
}

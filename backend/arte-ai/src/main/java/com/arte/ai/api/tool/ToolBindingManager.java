package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ResolvedToolBinding;
import com.arte.ai.pojo.tool.ToolBindingCommand;
import com.arte.ai.pojo.tool.ToolBindingView;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.List;
import java.util.Optional;

/**
 * 当前用户或工作空间的工具版本绑定管理接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolBindingManager {

    ResolvedToolBinding save(String ownerId, ToolBindingCommand command);

    Optional<ResolvedToolBinding> resolve(String ownerId, String workspaceId, String bindingId);

    default Optional<ResolvedToolBinding> resolveRequested(String ownerId, String workspaceId,
                                                           String bindingId, ToolReference requested) {
        return resolve(ownerId, workspaceId, bindingId).filter(binding -> binding.tool().equals(requested));
    }

    /**
     * 解析新运行的兼容版本；已创建的调用仍使用 resolveRequested 锁定具体版本。
     */
    default Optional<ResolvedToolBinding> resolveCompatible(String ownerId, String workspaceId,
                                                            String bindingId, ToolReference baseline) {
        return resolveRequested(ownerId, workspaceId, bindingId, baseline);
    }

    List<ResolvedToolBinding> listEnabled(String ownerId, String workspaceId);

    List<ToolBindingView> list(String ownerId, String workspaceId);
}

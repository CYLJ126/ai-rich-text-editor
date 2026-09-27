package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ResolvedToolBinding;
import com.arte.ai.pojo.tool.ToolBindingCommand;
import com.arte.ai.pojo.tool.ToolBindingView;

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

    List<ResolvedToolBinding> listEnabled(String ownerId, String workspaceId);

    List<ToolBindingView> list(String ownerId, String workspaceId);
}

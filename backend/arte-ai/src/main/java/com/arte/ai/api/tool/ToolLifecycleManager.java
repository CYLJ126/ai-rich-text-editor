package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolVersionView;

import java.util.List;
import java.util.Optional;

/**
 * 工具版本与发布生命周期管理接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolLifecycleManager {

    void publish(ToolReference reference);

    default void publish(ToolReference reference, com.arte.ai.pojo.tool.ToolPublishCommand command) {
        publish(reference);
    }

    void deprecate(ToolReference reference, String reason);

    void disable(ToolReference reference, String reason);

    Optional<ToolLifecycleStateEnum> getState(ToolReference reference);

    List<ToolVersionView> listVersions(String namespace, String name);
}

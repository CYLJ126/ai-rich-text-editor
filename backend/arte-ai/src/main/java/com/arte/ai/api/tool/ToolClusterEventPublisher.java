package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;
import com.arte.ai.pojo.tool.ToolReference;

/**
 * 工具目录集群变更发布接口。
 *
 * <p>未来页面编辑工具配置时，应在数据库事务中调用该接口。实现会在事务提交后广播，
 * 其他节点收到事件后重新构建本地运行时注册表。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolClusterEventPublisher {

    void publishProviderChanged(String providerId, ToolClusterEventTypeEnum eventType);

    void publishToolChanged(String providerId, ToolReference reference,
                            ToolClusterEventTypeEnum eventType);

    default void publishCatalogChanged(String providerId) {
        publishProviderChanged(providerId, ToolClusterEventTypeEnum.CATALOG_CHANGED);
    }
}

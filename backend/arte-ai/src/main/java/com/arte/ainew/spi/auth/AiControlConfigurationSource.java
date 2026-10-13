package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;

/**
 * 受信数据库控制面
 * <p>
 * 同步方法仅在启动或 AI 数据库调度线程调用；不从客户端接受授权配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface AiControlConfigurationSource {

    /**
     * 在本次调用中核验当前主体、范围和 context 的全部权限，返回包含该主体 Grant 的一致配置快照。
     * 调用方仅在一次配置校验内复用快照；后续业务边界重新读取，不能跨请求缓存授权。
     */
    NewAiProperties configuration(ExecutionContext context);

    NewAiProperties definitions();

    NewAiProperties.Grant grant(ExecutionContext context);
}

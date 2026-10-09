package com.arte.core.api.access;

import com.arte.core.pojo.access.ResourceAccess;
import com.arte.core.pojo.access.ResourceDescriptor;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.TrustedActor;

/**
 * 资源注册服务
 * <p>
 * 仅供受信领域创建流程使用，不暴露初始化 HTTP。必须加入正文创建的同线程同库事务，校验可信归属及创建资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ResourceRegistrationService {

    /**
     * 在受信领域的资源创建事务内登记初始 ACL 和授权。
     *
     * @param actor      经可信认证适配确认的当前操作者
     * @param descriptor 由受信领域提供的资源归属、状态与版本
     * @param meta       命令元数据，包含幂等操作键及追踪标识
     * @return 已登记的资源 ACL 根记录
     */
    ResourceAccess registerCreated(TrustedActor actor, ResourceDescriptor descriptor, CommandMeta meta);
}

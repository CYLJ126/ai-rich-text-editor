package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;

/**
 * 新空间插入完整公共关联后，同线程、同库原事务中准备业务默认配置；失败回滚整个创建命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface WorkspaceProvisioningParticipant {
    void provision(TrustedActor actor, Scope scope, String operationId);
}

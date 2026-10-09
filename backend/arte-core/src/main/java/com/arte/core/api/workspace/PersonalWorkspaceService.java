package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 个人初始化公共 API
 * <p>
 * 只接受有效 USER，同事务创建唯一租户、默认空间、负责人及偏好，禁止自报归属。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PersonalWorkspaceService {

    /**
     * 为有效用户初始化个人租户及默认空间，重复初始化复用已有关联。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param meta  命令元数据，包含幂等操作键及追踪标识
     * @return 初始化回执，包含个人租户、默认空间及关联信息
     */
    CommandReceipt<WorkspaceViews.PersonalWorkspaceView> initialize(TrustedActor actor, CommandMeta meta);
}

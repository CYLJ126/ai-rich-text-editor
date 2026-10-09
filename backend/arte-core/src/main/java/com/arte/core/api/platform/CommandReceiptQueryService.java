package com.arte.core.api.platform;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.TrustedActor;

/**
 * 原操作主体查询回执
 * <p>
 * 当前结果可见性重新检查，不可见与不存在统一，不返回存储信封。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface CommandReceiptQueryService {

    /**
     * 按操作 ID 查询原操作者当前有资格读取的命令回执。
     *
     * @param actor       经可信认证适配确认的当前操作者
     * @param operationId 目标操作 ID
     * @return 通过当前结果可见性检查的公开命令回执
     */
    CommandReceipt<?> get(TrustedActor actor, String operationId);
}

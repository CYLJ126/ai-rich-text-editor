package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.TrustedActor;

/**
 * 租户名称维护的独立业务资格，最终写入须使用事务内当前读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 17:11 ✾
 */
@FunctionalInterface
public interface TenantUpdatePolicy {

    boolean mayUpdate(TrustedActor actor, String tenantId, boolean current);
}

package com.arte.core.spi.workspace;

import com.arte.core.pojo.platform.TrustedActor;

/**
 * 受信环境的组织租户创建资格。未装配或未明确允许时关闭创建；不能从客户端自报角色推断。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface TenantProvisioningPolicy {
    /**
     * 判断可信操作者是否获准创建组织租户，未明确允许时返回 false。
     */
    boolean mayCreateOrganization(TrustedActor actor);
}

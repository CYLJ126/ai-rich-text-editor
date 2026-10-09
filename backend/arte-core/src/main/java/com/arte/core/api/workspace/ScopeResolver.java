package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.VerifiedScope;

/**
 * 验证当前主体、租户及空间成员和政策。
 * <p>
 * VerifiedScope 只是快照，最终写入使用事务门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ScopeResolver {

    /**
     * 验证当前操作者在所选租户与空间的成员资格及政策。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @return 验证后的范围与成员快照，最终写入仍需事务内重查
     * @throws com.arte.core.exception.platform.PlatformException 主体、成员资格或范围政策检查未通过
     */
    VerifiedScope verify(TrustedActor actor, Scope scope);
}

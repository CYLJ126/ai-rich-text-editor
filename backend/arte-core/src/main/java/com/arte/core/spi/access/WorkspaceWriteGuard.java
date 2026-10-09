package com.arte.core.spi.access;

import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.TrustedActor;

import java.util.List;
import java.util.function.Supplier;

/**
 * 最终提交门控
 * <p>
 * 只在调用方实际事务内执行，先锁主体门控，再同序锁租户、空间、成员／ACL，以当前读取重查资格后执行 work，不等待网络。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface WorkspaceWriteGuard {

    /**
     * 在调用方当前事务内锁定并重新校验权限，通过后执行 work 并返回其结果。
     * 租户治理仅锁主体、租户及租户成员，不要求空间成员资格；混合请求先锁全部租户，再锁目标空间及成员。
     */
    <T> T inCurrentTransaction(TrustedActor actor, List<AccessRequest> requests, Supplier<T> work);
}

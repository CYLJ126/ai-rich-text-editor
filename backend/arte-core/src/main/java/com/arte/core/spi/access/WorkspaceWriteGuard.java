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
     * 顺序约束覆盖实际外层事务：新主体须在所有租户锁前按 ID 递增取得，新租户父锁须按 ID 递增取得；已持有锁可重入。
     * 组合命令可先将完整目标集合传入本方法，再在 work 内按业务顺序调用；违反顺序时拒绝取新锁并强制事务回滚。
     */
    <T> T inCurrentTransaction(TrustedActor actor, List<AccessRequest> requests, Supplier<T> work);
}

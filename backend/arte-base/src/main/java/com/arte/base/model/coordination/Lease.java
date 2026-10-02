package com.arte.base.model.coordination;

import java.time.Instant;

/**
 * 执行归属、失效时间及 fencing token 的租约快照，不替代领域事务。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Lease(
        String leaseId,
        String ownerId,
        long fencingToken,
        Instant expiresAt
) {
}

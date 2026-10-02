package com.arte.base.model.change;

import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.resource.ResourceRef;

import java.time.Instant;

/**
 * 正式应用的记录快照；与目标领域新版本及应用去重在同一事务中提交。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ApplicationRecord(
        String applicationKey,
        String changeSetId,
        ResourceRef savedResource,
        PrincipalRef appliedBy,
        Instant appliedAt
) {
}

package com.arte.base.model.change;

import com.arte.base.model.resource.ResourceRef;

/**
 * 独立于生成请求的应用命令；领域事务负责 applicationKey 去重及 expectedVersion 校验。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ApplyRequest<P>(
        String applicationKey,
        String expectedVersion,
        ChangeSet<P> changeSet,
        ResourceRef adoptionRef
) {
}

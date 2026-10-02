package com.arte.base.model.change;

import com.arte.base.model.resource.ResourceRef;

/**
 * 变更应用结果；正式保存以返回的新资源版本为准，冲突信息与失败状态分别表达。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ChangeApplicationResult(
        String applicationKey,
        ChangeStatus status,
        ResourceRef savedResource,
        VersionConflict conflict
) {
}

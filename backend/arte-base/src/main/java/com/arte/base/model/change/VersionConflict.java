package com.arte.base.model.change;

/**
 * 条件应用失败时的版本冲突信息，不返回未授权内容。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record VersionConflict(
        String expectedVersion,
        String actualVersion
) {
}

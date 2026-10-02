package com.arte.base.model.change;

/**
 * 变更提案、草稿采纳与正式保存的状态；待保存不等于已应用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum ChangeStatus {
    PROPOSED,
    AWAITING_ADOPTION,
    AWAITING_SAVE,
    APPLIED,
    REJECTED,
    CONFLICT,
    INVALIDATED
}

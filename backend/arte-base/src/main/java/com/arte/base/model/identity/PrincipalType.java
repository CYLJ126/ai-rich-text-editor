package com.arte.base.model.identity;

/**
 * 主体种类；认证及主体有效性由服务端验证。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum PrincipalType {
    USER,
    SERVICE
}

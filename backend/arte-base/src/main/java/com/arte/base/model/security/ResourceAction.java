package com.arte.base.model.security;

/**
 * 稳定资源动作码的扩展端口；领域可用自己的枚举实现，不把角色或页面权限当作动作。
 */
@FunctionalInterface
public interface ResourceAction {

    String code();
}

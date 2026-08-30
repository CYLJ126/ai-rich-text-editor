package com.nip.app.common.constant;

/**
 * RBAC 相关的常量
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 16:34 ✾
 */
public class RbacConstant {

    private RbacConstant() {
    }

    /**
     * Jwt Token 的 payload 对应的 key
     */
    public static final String NIP_USER_NAME = "username";

    /**
     * 管理员角色编码
     */
    public static final String ADMIN_ROLE_CODE = "admin";
}

package com.arte.app.web.platform;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 新管理请求拒绝未声明字段，不能通过附加主体、归属、成员代次或定义快照表达权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
public interface StrictPlatformRequest {

    @JsonAnySetter
    default void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported management request field");
    }
}

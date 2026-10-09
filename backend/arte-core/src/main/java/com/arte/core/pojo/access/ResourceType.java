package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 服务器登记的资源类型名。值对象只验证格式；是否登记由资源／动作注册表判断。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceType(
        String value) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ResourceType {
        PlatformChecks.token(value, "value");
    }

    @JsonValue
    public String value() {
        return value;
    }
}

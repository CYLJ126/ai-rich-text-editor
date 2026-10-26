package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 服务器登记的域：动作名称
 * <p>
 * 不支持通配符，构造成功不等于获授权。
 *
 * @param value 动作名称
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Action(String value) {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Action {
        PlatformChecks.token(value, "value");
        PlatformChecks.require(value.contains(":"), "action requires domain prefix");
    }

    @JsonValue
    public String value() {
        return value;
    }
}

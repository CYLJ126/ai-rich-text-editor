package com.arte.ainew.serialization;

import com.arte.ainew.pojo.tool.ToolBindingSettings;

/**
 * 绑定语义摘要；集合先排序，保存层配置与最终配置分别摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public final class ToolBindingDigests {
    private ToolBindingDigests() {
    }

    public static Object settings(ToolBindingSettings value) {
        return CanonicalJson.fields("tool", value.tool(), "versionPolicy", value.versionPolicy(), "name", value.name(),
                "enabled", value.enabled(), "configuration", value.configuration(), "secrets", value.secrets(),
                "policy", value.policy(), "networkOrigins", value.networkOrigins() == null ? null
                        : value.networkOrigins().stream().map(Object::toString).sorted().toList());
    }
}

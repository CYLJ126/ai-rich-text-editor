package com.arte.ai.pojo.tool;

import java.util.Objects;

/**
 * 工具的稳定引用。工作流程和运行记录应固定到具体版本，以保证可重现性。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolReference(String namespace, String name, String version) {

    public ToolReference {
        namespace = requireText(namespace, "namespace");
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

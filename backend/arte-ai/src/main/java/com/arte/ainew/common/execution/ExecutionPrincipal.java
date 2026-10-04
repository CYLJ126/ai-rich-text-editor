package com.arte.ainew.common.execution;

import java.io.Serializable;
import java.util.Objects;

/**
 * 经服务端验证的执行主体。只保存稳定身份，不复制 Token、密码或在线用户对象。
 * subjectId 由身份提供者解析，不能用请求体自报的 ID 或用户名推算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionPrincipal(String subjectId, String subjectName, Kind kind) implements Serializable {
    public enum Kind { USER, SERVICE }

    public ExecutionPrincipal {
        requireText(subjectId, "subjectId");
        requireText(subjectName, "subjectName");
        Objects.requireNonNull(kind, "kind");
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

package com.arte.ai.validation;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.UUID;

/**
 * 最小聊天与其存储字段的结构检查；不验证身份、引用存在性或内容的实际授权。
 */
public final class ChatContractChecks {
    private ChatContractChecks() {
    }

    public static String identifier(String value, int maximumLength, String field) {
        value = ContractChecks.identifier(value, field);
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its storage length");
        }
        return value;
    }

    public static String optionalIdentifier(String value, int maximumLength, String field) {
        return value == null ? null : identifier(value, maximumLength, field);
    }

    public static ExecutionScope scope(ExecutionScope scope) {
        ContractChecks.required(scope, "scope");
        identifier(scope.tenantId(), 128, "tenantId");
        identifier(scope.workspaceId(), 128, "workspaceId");
        identifier(scope.principal().principalId(), 128, "principalId");
        return scope;
    }

    public static String digest(String value, String field) {
        ContractChecks.required(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " requires a lowercase SHA-256 digest");
        }
        return value;
    }

    public static String executionId(String value) {
        if (value == null) return null;
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("executionId must be a canonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("executionId must be a canonical UUID");
        }
        return value;
    }

    public static void positive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    public static void times(Instant createdAt, Instant updatedAt) {
        ContractChecks.required(createdAt, "createdAt");
        ContractChecks.required(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }
}

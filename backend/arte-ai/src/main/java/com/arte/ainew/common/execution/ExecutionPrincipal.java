package com.arte.ainew.common.execution;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;

import java.io.Serializable;
import java.util.Objects;

/**
 * 执行时的身份及认证名称快照。只保存稳定身份，不复制 Token、密码或在线用户对象。
 * subjectId 由身份提供者解析，不能用请求体自报的 ID 或用户名推算。
 * subjectName 保留当时认证／展示名称，供既有配置解析及桥接使用，不参与归属键。
 * 当前启停状态由 core Principal 及其身份提供者重新读取，不能从历史快照推断。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionPrincipal(String subjectId, String subjectName, Kind kind) implements Serializable {
    /**
     * 旧快照的字符串编码适配；主体类型语义与 core 的 PrincipalKindEnum 对齐。
     */
    public enum Kind {
        USER, SERVICE;

        public PrincipalKindEnum platformKind() {
            return switch (this) {
                case USER -> PrincipalKindEnum.USER;
                case SERVICE -> PrincipalKindEnum.SERVICE;
            };
        }

        public static Kind from(PrincipalKindEnum kind) {
            return switch (Objects.requireNonNull(kind, "kind")) {
                case USER -> USER;
                case SERVICE -> SERVICE;
            };
        }
    }

    public ExecutionPrincipal {
        requireText(subjectId, "subjectId");
        requireText(subjectName, "subjectName");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * 从可信边界刚读取的当前有效主体构造执行快照；名称也须由该边界提供。
     * 不把状态存入快照，后续派发、读取及提交仍须重新授权。
     */
    public static ExecutionPrincipal from(Principal current, String currentSubjectName) {
        Objects.requireNonNull(current, "current");
        if (current.status() != PrincipalStatusEnum.ACTIVE) {
            throw new IllegalArgumentException("Execution requires an active principal");
        }
        return new ExecutionPrincipal(current.subjectId(), currentSubjectName, Kind.from(current.kind()));
    }

    /**
     * 只比对稳定 ID 与主体类型，不比对名称或当前状态；返回 true 不表示当前已授权。
     * 调用方必须独立校验名称绑定、生命周期和当前启用状态。
     */
    public boolean sameIdentityAs(Principal current) {
        return current != null && subjectId.equals(current.subjectId()) && kind.platformKind() == current.kind();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

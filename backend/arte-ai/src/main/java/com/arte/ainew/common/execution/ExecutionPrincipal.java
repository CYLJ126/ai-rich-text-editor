package com.arte.ainew.common.execution;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;

import java.io.Serializable;
import java.util.Objects;

/**
 * 执行时的身份及认证名称快照
 * <p>
 * 只保存稳定身份，不复制 Token、密码或在线用户对象。
 * 当前启停状态由 core Principal 及其身份提供者重新读取，不能从历史快照推断。
 *
 * @param subjectId   由可信身份提供者解析的稳定主体标识，不能为空白；用于执行归属及身份比对，不从请求体自报值或认证名称推算。
 * @param subjectName 本次快照记录的认证或展示名称，不能为空白，由可信边界提供；用于既有配置解析及身份桥接，不作为资源或执行归属键。
 * @param kind        主体类型，不能为空，用于区分用户与服务身份并与 core Principal 的类型对应；不表示当前启用状态或已获执行权限。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionPrincipal(String subjectId, String subjectName, Kind kind) implements Serializable {

    /**
     * 执行主体的身份类别，用于身份比对及用户／服务执行边界校验，不保存权限或当前状态。
     * 保留旧执行快照的字符串编码，通过 platformKind／from 与 core 的 PrincipalKindEnum 双向转换。
     */
    public enum Kind {
        // 用户主体，表示由可信身份提供者确认的用户身份，使用用户入口时仍须检查当前资格。
        USER,
        // 服务主体，表示受信后台或系统服务身份，执行范围须由独立授权限定，不默认拥有用户权限。
        SERVICE;

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

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

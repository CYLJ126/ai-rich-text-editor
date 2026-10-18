package com.arte.ainew.pojo.tool;

import com.arte.ainew.pojo.execution.StoreOutcome;

import java.util.Objects;

/**
 * 工具绑定结果
 * <p>
 * 可预期的绑定领域决定；
 * 数据库及损坏故障仍通过 onError 传播。
 *
 * @param outcome        成功 APPLIED 或可预期拒绝，不与外层管理回执的 APPLIED／REPLAYED 混用。
 * @param rejection      稳定原因，无配置值或异常正文。
 * @param value          仅成功时携带不可变结果。
 * @param currentVersion 已核实当前 owner 后可返回的治理版本，缺失或归属不匹配时为空。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingResult<T>(StoreOutcome.Code outcome, Rejection rejection, T value, Long currentVersion) {

    public enum Rejection {
        NONE, NOT_FOUND, OWNER_MISMATCH, VERSION_CONFLICT, TOOL_ID_MISMATCH, USE_UPGRADE,
        TOOL_UNAVAILABLE, BINDING_DISABLED, CONFIGURATION_INVALID, POLICY_CONFLICT, NETWORK_NOT_ALLOWED,
        SECRET_UNSUPPORTED, CHAIN_INVALID, CHAIN_LIMIT_EXCEEDED, PREVIEW_CHANGED, INCOMPATIBLE, NO_CHANGE, EXECUTOR_UNAVAILABLE
    }

    public ToolBindingResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(rejection, "rejection");
        if ((outcome == StoreOutcome.Code.APPLIED) != (value != null) || (value != null) != (rejection == Rejection.NONE))
            throw new IllegalArgumentException("Only successful binding results contain a value");
        if (currentVersion != null && currentVersion < 0)
            throw new IllegalArgumentException("Invalid current binding version");
    }

    public boolean successful() {
        return value != null;
    }

    public static <T> ToolBindingResult<T> success(T value, long version) {
        return new ToolBindingResult<>(StoreOutcome.Code.APPLIED, Rejection.NONE, Objects.requireNonNull(value), version);
    }

    public static <T> ToolBindingResult<T> rejected(Rejection reason, Long version) {
        var code = switch (reason) {
            case NOT_FOUND -> StoreOutcome.Code.NOT_FOUND;
            case OWNER_MISMATCH -> StoreOutcome.Code.OWNER_MISMATCH;
            case VERSION_CONFLICT -> StoreOutcome.Code.VERSION_CONFLICT;
            default -> StoreOutcome.Code.INVALID_STATE;
        };
        return new ToolBindingResult<>(code, reason, null, version);
    }
}

package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;

import java.util.Objects;

/**
 * 内部个人绑定管理命令
 * <p>
 * owner／actor 由可信入口分别提供。
 *
 * @param action                完整保存、启停或显式升级。
 * @param bindingId             创建 SAVE 时为空，由服务端按 owner／命令派生；其他情况为稳定 ID。
 * @param expectedVersion       新建为 0，修改为当前治理版本，不静默覆盖。
 * @param idempotencyKey        真实 owner 及操作者范围内跨绑定／动作共享的命令键。
 * @param reason                审计原因，不写日志。
 * @param settings              SAVE／UPGRADE 的完整新配置，SET_ENABLED 为空。
 * @param enabled               SET_ENABLED 的目标状态，其他动作为空。
 * @param expectedPreviewDigest UPGRADE 必须提交服务端预览摘要，其他动作为空。
 * @param acceptIncompatible    仅 UPGRADE 可显式确认无审核兼容路径的版本选择，不代替当前授权。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingCommand(Action action, String bindingId, long expectedVersion, String idempotencyKey,
                                 String reason, ToolBindingSettings settings, Boolean enabled,
                                 String expectedPreviewDigest, boolean acceptIncompatible) {

    public enum Action {SAVE, SET_ENABLED, UPGRADE}

    public ToolBindingCommand {
        Objects.requireNonNull(action, "action");
        ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE - 1);
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        ContractChecks.text(reason, "reason", 1024);
        if (bindingId == null)
            ContractChecks.require(action == Action.SAVE && expectedVersion == 0, "Only creation omits bindingId");
        else {
            ContractChecks.id(bindingId, "bindingId");
            ContractChecks.require(expectedVersion > 0, "Existing binding requires a version");
        }
        ContractChecks.require((action == Action.SET_ENABLED) == (enabled != null), "Only state changes supply enabled");
        ContractChecks.require((action != Action.SET_ENABLED) == (settings != null), "Save and upgrade require complete settings");
        if (action == Action.UPGRADE) ContractChecks.digest(expectedPreviewDigest, "expectedPreviewDigest");
        else
            ContractChecks.require(expectedPreviewDigest == null && !acceptIncompatible, "Only upgrades accept preview and incompatibility decisions");
    }
}

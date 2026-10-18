package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * 一次显式升级预览
 * <p>
 * 不改变绑定或创建解析工件。
 *
 * @param bindingId       当前绑定身份。
 * @param expectedVersion 预览所针对的治理版本，确认时仍须匹配。
 * @param currentBaseline 当前管理修订的固定基准，无可见绑定时为空。
 * @param currentTool     当前可解析版本；不可用时回显原固定基准，不伪装可执行。
 * @param compatible      从当前实际版本到目标存在显式审核兼容路径。
 * @param changes         需要展示的变更类别，不包含 Secret 值。
 * @param proposed        目标方案或稳定拒绝；非法配置时不提供可确认方案。
 * @param digest          服务端预览摘要，目标方案失败时为空。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingUpgradePreview(String bindingId, long expectedVersion, DefinitionRef currentBaseline,
                                        DefinitionRef currentTool, boolean compatible, List<Change> changes,
                                        ToolBindingResult<ToolBindingResolution> proposed, String digest) {

    public enum Change {TOOL_VERSION, VERSION_POLICY, CONFIGURATION, SECRET_REFERENCES, EXECUTION_POLICY, NETWORK_ORIGINS, RISK, CAPABILITIES, ENABLED, NAME}

    public ToolBindingUpgradePreview {
        ContractChecks.id(bindingId, "bindingId");
        ContractChecks.range(expectedVersion, "expectedVersion", 1, Long.MAX_VALUE - 1);
        changes = ContractChecks.list(changes, "changes", 0, Change.values().length);
        ContractChecks.unique(changes, "changes");
        Objects.requireNonNull(proposed);
        if (proposed.successful()) ContractChecks.digest(digest, "digest");
        else ContractChecks.require(digest == null, "Failed preview cannot be confirmed");
    }
}

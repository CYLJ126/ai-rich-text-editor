package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.core.enums.platform.PrincipalKindEnum;

import java.time.Instant;
import java.util.Objects;

/**
 * 与状态同事务保存的不可变管理回执兼变更审计；重放返回首次决定，不返回当前状态冒充原结果。
 *
 * @param commandId      服务端按真实主体与幂等键派生的命令身份。
 * @param actorSubjectId 实际操作者的稳定主体。
 * @param actorKind      实际主体类别，不使用执行归属代替操作者。
 * @param command        原始管理语义，包括原因及首次发布材料。
 * @param outcome        首次领域决定，成功为 APPLIED；拒绝也固定保存。
 * @param rejection      拒绝的稳定原因，成功为 NONE，不回显实际业务值。
 * @param release        决定时的状态；工具不存在时为 null。
 * @param validation     发布／重新启用的服务端预检，其他动作为空。
 * @param recordedAt     服务端 UTC 微秒记录时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public record ToolReleaseReceipt(String commandId, String actorSubjectId, PrincipalKindEnum actorKind,
                                 ToolReleaseCommand command, StoreOutcome.Code outcome, Rejection rejection,
                                 ToolRelease release,
                                 ToolPublicationValidation validation, Instant recordedAt) {
    public enum Rejection {
        NONE, TOOL_NOT_FOUND, VERSION_CONFLICT, INVALID_TRANSITION, VALIDATION_FAILED,
        CONTRACT_CHANGED, INCOMPATIBLE, AMBIGUOUS_COMPATIBILITY, EXPIRED
    }

    public ToolReleaseReceipt {
        ContractChecks.digest(commandId, "commandId");
        ContractChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(rejection, "rejection");
        ContractChecks.require((outcome == StoreOutcome.Code.APPLIED) == (rejection == Rejection.NONE), "Invalid receipt rejection");
        ContractChecks.require(outcome == StoreOutcome.Code.APPLIED || outcome == StoreOutcome.Code.NOT_FOUND
                || outcome == StoreOutcome.Code.VERSION_CONFLICT || outcome == StoreOutcome.Code.INVALID_STATE, "Invalid release decision");
        ContractChecks.require((outcome == StoreOutcome.Code.NOT_FOUND) == (release == null), "Only missing tools have no release state");
        ContractChecks.require(release == null || command.tool().equals(release.tool()), "Receipt tool identity mismatch");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}

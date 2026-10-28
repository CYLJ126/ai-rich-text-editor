package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Instant;
import java.util.Objects;

/**
 * 与绑定指针／不可变修订同事务保存的原始管理回执及审计
 * <p>
 * 重放不返回当前配置冒充原决定。
 *
 * @param commandId      owner、真实操作者和幂等键派生的命令标识。
 * @param actorSubjectId 实际操作者稳定主体。
 * @param actorKind      实际操作者类别。
 * @param owner          可信个人归属，不由请求体指定。
 * @param bindingId      新建时的服务端固定标识或原绑定标识。
 * @param command        首次原始管理语义，含非敏感设置及原因。
 * @param result         首次领域决定，不是外层回执首次／重放标记。
 * @param recordedAt     首次记录的 UTC 微秒时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingReceipt(String commandId, String actorSubjectId, PrincipalKindEnum actorKind,
                                 ExecutionOwner owner,
                                 String bindingId, ToolBindingCommand command,
                                 ToolBindingResult<ToolBindingDefinition> result, Instant recordedAt) {
    public ToolBindingReceipt {
        ContractChecks.digest(commandId, "commandId");
        ContractChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(actorKind);
        Objects.requireNonNull(owner);
        ContractChecks.require(owner.subjectId().equals(actorSubjectId), "Only personal binding commands are supported");
        ContractChecks.id(bindingId, "bindingId");
        Objects.requireNonNull(command);
        Objects.requireNonNull(result);
        Objects.requireNonNull(recordedAt);
        ContractChecks.require(result.value() == null || result.value().bindingId().equals(bindingId) && result.value().owner().equals(owner), "Receipt ownership mismatch");
    }
}

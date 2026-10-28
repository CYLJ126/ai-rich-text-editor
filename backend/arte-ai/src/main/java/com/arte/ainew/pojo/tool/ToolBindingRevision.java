package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的完整绑定修订
 * <p>
 * 不把当前权限或工具活跃状态冻结为长期资格。
 *
 * @param definition type=binding、稳定 bindingId、rN 修订。
 * @param owner      固定个人归属。
 * @param settings   本修订完整设置，启停也创建新修订。
 * @param createdAt  本修订成功提交的 UTC 微秒时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingRevision(DefinitionRef definition, ExecutionOwner owner, ToolBindingSettings settings,
                                  Instant createdAt) {

    public ToolBindingRevision {
        Objects.requireNonNull(definition, "definition").requireType("binding");
        ContractChecks.require(definition.version().matches("r[1-9][0-9]{0,18}"), "Invalid binding revision");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

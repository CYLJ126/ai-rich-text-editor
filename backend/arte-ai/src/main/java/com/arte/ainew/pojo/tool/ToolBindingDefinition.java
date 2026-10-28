package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;
import java.util.Objects;

/**
 * 个人绑定的稳定身份与当前修订指针
 * <p>
 * 归属和稳定工具 ID 创建后不可变。
 *
 * @param bindingId 服务端生成的稳定标识，不复用为工具身份。
 * @param owner     真实租户、工作空间及个人主体，不能从外部请求体自报。
 * @param toolId    固定的工具稳定身份，升级只换该工具的版本。
 * @param version   当前治理版本，每次成功管理命令创建同编号的不可变修订。
 * @param name      当前修订的展示名投影，方便有限分页，不作为授权身份。
 * @param enabled   当前启停投影，停止后不能凭历史快照发起下一次使用。
 * @param createdAt 创建 UTC 微秒时间。
 * @param updatedAt 最近成功变更 UTC 微秒时间。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public record ToolBindingDefinition(String bindingId, ExecutionOwner owner, String toolId, long version,
                                    String name, boolean enabled, Instant createdAt, Instant updatedAt) {
    public ToolBindingDefinition {
        ContractChecks.id(bindingId, "bindingId");
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(toolId, "toolId");
        ContractChecks.range(version, "version", 1, Long.MAX_VALUE);
        ContractChecks.text(name, "name", 200);
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }

    /**
     * 管理修订引用；不同于已解析执行计划的 p1-* 绑定引用。
     */
    public DefinitionRef revision() {
        return new DefinitionRef("binding", bindingId, "r" + version);
    }
}

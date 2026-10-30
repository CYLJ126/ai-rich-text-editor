package com.arte.ainew.pojo.budget;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.Objects;

/**
 * 预算定义
 * <p>
 * 表达预算引用、归属、额度和关联费率；不包含账户的预留、已计费用或结算状态。
 * 账户的预留、已计费用及结算状态继续由原账本和{@link BudgetService}管理。
 *
 * @param budgetRef 预算定义的稳定引用，用于定位对应预算账户，不表示账户当前余额
 * @param owner     预算归属，固定租户、工作空间及稳定主体，不因模型共享或配置来源切换而改变
 * @param limit     预算额度及币种；可用额度还须结合实际账户的预留和已计费用计算
 * @param rate      关联的固定版本费率引用，类型必须为 rate；使用时须校验与模型或工具费率及币种兼容
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public record BudgetDefinition(String budgetRef, ExecutionOwner owner, Money limit, DefinitionRef rate) {

    public BudgetDefinition {
        ContractChecks.id(budgetRef, "budgetRef");
        Objects.requireNonNull(owner);
        Objects.requireNonNull(limit);
        Objects.requireNonNull(rate).requireType("rate");
    }
}

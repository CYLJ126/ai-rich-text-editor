package com.arte.ainew.application.control;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.spi.auth.BudgetReconciliationAccess;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.core.constant.platform.PlatformResourceTypes;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

/**
 * 预算账户查询服务
 * <p>
 * 当前授权下读取固定预算账户，不初始化账户或修改账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public final class BudgetAccountQueryService {

    private final BudgetDefinitionProvider definitions;
    private final AdmissionAuthorization authorization;
    private final BudgetService budgets;

    /**
     * 创建预算账户查询服务，仅读取与当前授权及预算定义一致的已有账本。
     *
     * @param definitions   当前可用预算定义的解析服务
     * @param authorization 当前读取者的操作授权服务
     * @param budgets       已有预算账户的查询服务
     */
    public BudgetAccountQueryService(BudgetDefinitionProvider definitions, AdmissionAuthorization authorization, BudgetService budgets) {
        this.definitions = definitions;
        this.authorization = authorization;
        this.budgets = budgets;
    }

    private BudgetReconciliationAccess access;

    /**
     * 组合模块安装账户责任及公共授权投影。
     *
     * @param access 具体预算账户的授权及责任查询适配器
     */
    public void accountAccess(BudgetReconciliationAccess access) {
        this.access = access;
    }

    /**
     * 根据账本所属租户和预算引用构造公共账户资源引用，供授权及责任查询使用。
     *
     * @param account 目标预算账户
     * @return 目标账户的公共资源引用，构造引用本身不授予权限
     */
    public static ResourceRef accountRef(BudgetCommands.Account account) {
        return new ResourceRef(account.owner().tenantId(),
                PlatformResourceTypes.BUDGET_ACCOUNT, account.budgetRef());
    }

    /**
     * 查询当前读取者可见的账户核对责任；未安装适配器时，按核对操作配置返回自行处理或未指派提示。
     *
     * @param account 已按当前身份核验的目标预算账户
     * @param actor   真实读取者的执行上下文
     * @return 当前核对责任投影，展示结果不代替后续操作授权
     */
    public Mono<BudgetReconciliationAccess.Handling> handling(BudgetCommands.Account account, ExecutionContext actor) {
        return authorization.isOperationConfigured(actor, AdmissionAuthorization.BUDGET_ADMIN).flatMap(admin -> access == null
                ? Mono.just(new BudgetReconciliationAccess.Handling(admin ? "SELF_SERVICE" : "UNASSIGNED", null))
                : access.handling(account, actor, admin));
    }

    /**
     * 本人预算与处理责任的只读组合。
     *
     * @param account  已按当前身份核验的账本
     * @param handling 当前可达处理责任，不授予操作权
     */
    public record View(BudgetCommands.Account account,
                       BudgetReconciliationAccess.Handling handling) {
    }

    /**
     * 先核验并读取本人预算账户，再组合当前可见的核对责任信息。
     *
     * @param budgetRef 预算引用
     * @param actor     当前读取者的执行上下文
     * @return 已核验的预算账本及核对责任只读视图
     */
    public Mono<View> view(String budgetRef, ExecutionContext actor) {
        return account(budgetRef, actor).flatMap(account -> handling(account, actor).map(handling ->
                new View(account, handling)));
    }

    /**
     * 校验读取权限、预算定义与账户归属，返回额度及费率版本均与定义一致的已有账本。
     * 预算不可用、账户未初始化或配置冲突时返回对应异常，不创建账户或修正账本。
     *
     * @param budgetRef 需要读取的预算引用
     * @param context   当前读取者的执行上下文
     * @return 属于当前执行主体且与预算定义一致的账户
     */
    public Mono<BudgetCommands.Account> account(String budgetRef, ExecutionContext context) {
        ContractChecks.id(budgetRef, "budgetRef");
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            ExecutionOwner owner = ExecutionOwners.from(current);
            return definitions.resolve(budgetRef, current, BudgetDefinitionProvider.Operation.READ).flatMap(definition -> {
                if (!budgetRef.equals(definition.budgetRef()) || !owner.equals(definition.owner())) {
                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE));
                }
                return budgets.account(owner, budgetRef)
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED)))
                        .map(account -> {
                            if (!account.budgetRef().equals(budgetRef) || !account.owner().equals(owner)
                                    || !account.limit().equals(definition.limit()) || !account.rateVersion().equals(definition.rate())) {
                                throw new AdmissionException(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT);
                            }
                            return account;
                        });
            });
        });
    }
}

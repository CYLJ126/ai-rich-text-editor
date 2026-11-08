package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.core.pojo.access.ResourceRef;
import reactor.core.publisher.Mono;

/**
 * 具体预算账户的公共授权边界
 * <p>
 * 真实 actor 不得替换为目标 owner。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 17:30 ✾
 */
public interface BudgetReconciliationAccess {

    /**
     * 当前财务读取或提交资格；许可仍须在实际读取／结算事务内重检。
     */
    Mono<Permit> authorize(ResourceRef accountRef, ExecutionContext actor, boolean submitting);

    /**
     * 本人账户的处理责任投影；展示字段不授予后续权限。
     */
    Mono<Handling> handling(BudgetCommands.Account account, ExecutionContext actor, boolean budgetAdmin);

    /**
     * 账户处理状态。
     *
     * @param status      SELF_SERVICE、ASSIGNED 或 UNASSIGNED
     * @param responsible 当前有权且可达的具名主责任人，其余状态为空
     */
    record Handling(String status, Responsible responsible) {
    }

    /**
     * 获授权可见的主责任人，不包含私人联系方式。
     *
     * @param subjectId   稳定主体 ID
     * @param displayName 可见名称；不可用时可为空
     */
    record Responsible(String subjectId, String displayName) {
    }

    /**
     * 原事务重检器：锁住公共治理范围后，才允许锁定调用和账本。
     */
    interface Permit {
        /**
         * 由服务端按真实账本解析的目标账户。
         */
        BudgetCommands.Account account();

        /**
         * 目标账户公共资源引用。
         */
        ResourceRef accountRef();

        /**
         * 读取许可不得用于提交，即使同一目标仍可读取。
         */
        boolean allowsConfirmation();

        /**
         * 在业务行锁之前先取得主体／租户／空间门控，不锁预算账本行。
         */
        void lockScopeInCurrentTransaction();

        /**
         * 每次原事务重检当前身份、成员、公共账户动作及提交操作资格。
         */
        String requireInCurrentTransaction();

        /**
         * 首次核对的独立公共审计，与账本和回执同事务提交。
         */
        void auditInCurrentTransaction(Reconciliation.Receipt receipt);
    }
}

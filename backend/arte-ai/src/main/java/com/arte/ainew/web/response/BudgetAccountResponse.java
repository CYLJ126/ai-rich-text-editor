package com.arte.ainew.web.response;

import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.spi.auth.BudgetReconciliationAccess;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * 账本快照
 * <p>
 * 金额采用十进制字符串保留精度，available 允许为负，不暴露账户 owner。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public record BudgetAccountResponse(String budgetRef, String currency, String limit, String held,
                                    String charged, String available, DefinitionRef rateVersion, long version,
                                    ResourceRef accountRef,
                                    BudgetReconciliationAccess.Handling reconciliationHandling) {

    public static BudgetAccountResponse from(BudgetCommands.Account account) {
        return from(account, new BudgetReconciliationAccess.Handling("UNASSIGNED", null));
    }

    public static BudgetAccountResponse from(BudgetCommands.Account account, BudgetReconciliationAccess.Handling handling) {
        return new BudgetAccountResponse(account.budgetRef(), account.limit().currency().getCurrencyCode(),
                account.limit().amount().toPlainString(), account.held().amount().toPlainString(),
                account.charged().amount().toPlainString(), account.available().toPlainString(),
                account.rateVersion(), account.version(), BudgetAccountQueryService.accountRef(account), handling);
    }
}

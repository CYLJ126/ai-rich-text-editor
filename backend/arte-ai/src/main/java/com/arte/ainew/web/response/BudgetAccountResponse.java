package com.arte.ainew.web.response;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.budget.BudgetCommands;

/**
 * 账本快照；金额采用十进制字符串保留精度，available 允许为负，不暴露账户 owner。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public record BudgetAccountResponse(String budgetRef, String currency, String limit, String held,
                                    String charged, String available, DefinitionRef rateVersion, long version) {

    public static BudgetAccountResponse from(BudgetCommands.Account account) {
        return new BudgetAccountResponse(account.budgetRef(), account.limit().currency().getCurrencyCode(),
                account.limit().amount().toPlainString(), account.held().amount().toPlainString(),
                account.charged().amount().toPlainString(), account.available().toPlainString(),
                account.rateVersion(), account.version());
    }
}

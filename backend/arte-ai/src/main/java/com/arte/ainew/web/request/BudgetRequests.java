package com.arte.ainew.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 预算账户选择条件；不接受客户端声明的 owner、权限或金额。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public final class BudgetRequests {

    private BudgetRequests() {
    }

    public record Query(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String budgetRef) {
    }
}

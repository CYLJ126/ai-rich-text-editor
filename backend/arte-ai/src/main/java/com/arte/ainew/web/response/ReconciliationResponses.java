package com.arte.ainew.web.response;

import com.arte.ainew.application.budget.ManualReconciliationService;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.execution.Invocation;

import java.time.Instant;

/**
 * 金额传输使用无指数十进制字符串，避免浏览器浮点精度或科学计数显示。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class ReconciliationResponses {
    private ReconciliationResponses() {
    }

    public record Amount(String amount, String currency) {
        static Amount from(Money money) {
            return new Amount(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }

    public record Pending(String invocationId, String conversationId, Invocation.State state, long invocationVersion,
                          String reservationId, long reservationVersion, String budgetRef, Amount reserved,
                          String remoteRequestId, Instant acceptedAt) {
        static Pending from(Reconciliation.Pending value) {
            return new Pending(value.invocationId(), value.conversationId(), value.state(), value.invocationVersion(),
                    value.reservationId(), value.reservationVersion(), value.budgetRef(), Amount.from(value.reserved()),
                    value.remoteRequestId(), value.acceptedAt());
        }
    }

    public record PendingPage(ConversationPage<Pending> page, boolean canReconcile) {
        public static PendingPage from(ManualReconciliationService.PendingPage value) {
            var page = value.page();
            return new PendingPage(new ConversationPage<>(page.current(), page.size(), page.total(),
                    page.records().stream().map(Pending::from).toList()), value.canReconcile());
        }
    }

    public record Receipt(String key, String invocationId, String reservationId, String budgetRef, Amount charge,
                          String evidenceRef, String note, ExecutionPrincipal reviewer, String traceId,
                          Invocation.State state, long invocationVersion, long reservationVersion,
                          Instant confirmedAt) {
        public static Receipt from(Reconciliation.Receipt value) {
            return new Receipt(value.key(), value.invocationId(), value.reservationId(), value.budgetRef(), Amount.from(value.charge()),
                    value.evidenceRef(), value.note(), value.reviewer(), value.traceId(), value.state(), value.invocationVersion(),
                    value.reservationVersion(), value.confirmedAt());
        }
    }
}

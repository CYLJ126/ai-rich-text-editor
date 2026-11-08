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
                          String remoteRequestId, Instant acceptedAt, Instant pendingSince) {
        static Pending from(Reconciliation.Pending value) {
            return new Pending(value.invocationId(), value.conversationId(), value.state(), value.invocationVersion(),
                    value.reservationId(), value.reservationVersion(), value.budgetRef(), Amount.from(value.reserved()),
                    value.remoteRequestId(), value.acceptedAt(), value.pendingSince());
        }
    }

    public record PendingPage(ConversationPage<Pending> page, boolean canReconcile) {
        public static PendingPage from(ManualReconciliationService.PendingPage value) {
            var page = value.page();
            return new PendingPage(new ConversationPage<>(page.current(), page.size(), page.total(),
                    page.records().stream().map(Pending::from).toList()), value.canReconcile());
        }
    }

    /**
     * 可公开的核对人标识，不公开可能是邮箱或手机号的登录名。
     *
     * @param subjectId   真实操作者稳定标识
     * @param displayName 获授权显示名；没有独立可见目录时为空
     * @param kind        主体类型
     */
    public record Reviewer(String subjectId, String displayName, ExecutionPrincipal.Kind kind) {
        static Reviewer from(ExecutionPrincipal value) {
            return new Reviewer(value.subjectId(), null, value.kind());
        }
    }

    public record Receipt(String key, String invocationId, String reservationId, String budgetRef, Amount charge,
                          String evidenceRef, String note, Reviewer reviewer, String traceId,
                          Invocation.State state, long invocationVersion, long reservationVersion,
                          Instant confirmedAt) {
        public static Receipt from(Reconciliation.Receipt value) {
            return new Receipt(value.key(), value.invocationId(), value.reservationId(), value.budgetRef(), Amount.from(value.charge()),
                    value.evidenceRef(), value.note(), Reviewer.from(value.reviewer()), value.traceId(), value.state(), value.invocationVersion(),
                    value.reservationVersion(), value.confirmedAt());
        }
    }
}

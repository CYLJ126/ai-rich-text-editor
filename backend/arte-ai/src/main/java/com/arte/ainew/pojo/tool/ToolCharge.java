package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;

import java.util.Objects;

/**
 * 仅受信适配器可提供的已确认账单；缺失表示未知，不能根据业务成功、失败或取消推导零费用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public record ToolCharge(Money amount, String evidenceRef, Kind kind) implements java.io.Serializable {

    public enum Kind {PROVIDER_BILL, NO_INVOCATION}

    public ToolCharge(Money amount, String evidenceRef) {
        this(amount, evidenceRef, Kind.PROVIDER_BILL);
    }

    public ToolCharge {
        Objects.requireNonNull(amount);
        ContractChecks.id(evidenceRef, "tool bill evidence");
        Objects.requireNonNull(kind);
        ContractChecks.require(kind != Kind.NO_INVOCATION || amount.amount().signum() == 0, "Non-invocation proof requires zero cost");
    }
}

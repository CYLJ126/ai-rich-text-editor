package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 单一币种的 Run 附加上限，不另扣一份账户费用。
 *
 * @param runId   根执行身份，归属由原 Run 校验。
 * @param limit   固定限额 L，各币种分别约束。
 * @param charged 已确认的真实费用 C，允许因异常账单超过 L。
 * @param held    未收敛的报价预留 H，取消或到期不自动释放。
 * @param blocked 报价／限额异常的锁存标志，阻止该 Run 的新外发。
 * @param version 本币种预算版本，独立于 Run 检查点版本。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public record ToolChatBudget(String runId, Money limit, Money charged, Money held, boolean blocked, long version) {

    public ToolChatBudget {
        ContractChecks.id(runId, "Run budget ID");
        Objects.requireNonNull(limit);
        Objects.requireNonNull(charged);
        Objects.requireNonNull(held);
        ContractChecks.require(limit.currency().equals(charged.currency()) && limit.currency().equals(held.currency()), "Run budget currency mismatch");
        ContractChecks.range(version, "Run budget version", 0, Long.MAX_VALUE - 1);
        ContractChecks.require(blocked || charged.amount().add(held.amount()).compareTo(limit.amount()) <= 0, "Run budget exceeds limit without anomaly");
    }

    /**
     * 可能为负；真实超额账单必须记录，blocked 一经出现不自动恢复派发。
     */
    public BigDecimal available() {
        return limit.amount().subtract(charged.amount()).subtract(held.amount());
    }
}

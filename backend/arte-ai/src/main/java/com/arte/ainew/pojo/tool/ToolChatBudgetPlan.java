package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode;
import com.arte.core.pojo.reference.DefinitionRef;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Run 固定的币种上限及子能力账户映射；仅由受信选择器生成，不绑定客户端或模型输出。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public record ToolChatBudgetPlan(List<Money> limits, List<Selection> selections) {

    /**
     * 固定模型费率字节，沿用现有每百万 Token、18 位 HALF_UP 计价，恢复不跟随新价格。
     */
    public record ModelPrice(Money inputPerMillion, Money outputPerMillion) {
        public ModelPrice {
            Objects.requireNonNull(inputPerMillion);
            Objects.requireNonNull(outputPerMillion);
            ContractChecks.require(inputPerMillion.currency().equals(outputPerMillion.currency()), "Model price currency mismatch");
        }

        public Money quote(long input, long output) {
            ContractChecks.require(input >= 0 && output >= 0, "Negative quote tokens");
            return new Money(inputPerMillion.amount().multiply(BigDecimal.valueOf(input))
                    .add(outputPerMillion.amount().multiply(BigDecimal.valueOf(output)))
                    .divide(BigDecimal.valueOf(1_000_000), 18, RoundingMode.HALF_UP), inputPerMillion.currency());
        }
    }

    /**
     * 每个固定绑定只使用一个已授权账户；模型与收费工具 CHARGE，免费工具无账户；Token 单价与工具按次上界分别表达。
     */
    public record Selection(DefinitionRef binding, DefinitionRef capability, CapabilityDescriptor.Kind kind,
                            BillingMode mode, String budgetRef, DefinitionRef rate, ModelPrice price,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ToolPrice toolPrice) {
        public Selection(DefinitionRef binding, DefinitionRef capability, CapabilityDescriptor.Kind kind, BillingMode mode, String budgetRef, DefinitionRef rate, ModelPrice price) {
            this(binding, capability, kind, mode, budgetRef, rate, price, null);
        }
        public Selection {
            Objects.requireNonNull(binding).requireType("binding");
            Objects.requireNonNull(capability).requireType("capability");
            Objects.requireNonNull(kind);
            Objects.requireNonNull(mode);
            if (mode == BillingMode.FREE) {
                ContractChecks.require(kind == CapabilityDescriptor.Kind.TOOL && budgetRef == null && rate == null && price == null && toolPrice == null,
                        "FREE selection must be an account-neutral tool");
            } else {
                ContractChecks.require(kind == CapabilityDescriptor.Kind.GENERATION ? price != null && toolPrice == null
                        : kind == CapabilityDescriptor.Kind.TOOL && price == null && toolPrice != null && toolPrice.rate().equals(rate), "Pricing kind mismatch");
                ContractChecks.id(budgetRef, "selected budgetRef");
                Objects.requireNonNull(rate).requireType("rate");
            }
        }
    }

    public ToolChatBudgetPlan {
        limits = ContractChecks.list(limits, "Run currency limits", 1, 8).stream()
                .sorted(Comparator.comparing(m -> m.currency().getCurrencyCode())).toList();
        ContractChecks.unique(limits.stream().map(Money::currency).toList(), "Run currencies");
        selections = ContractChecks.list(selections, "Run budget selections", 1, 33).stream()
                .sorted(Comparator.comparing((Selection s) -> s.binding().type()).thenComparing(s -> s.binding().id()).thenComparing(s -> s.binding().version())).toList();
        ContractChecks.unique(selections.stream().map(Selection::binding).toList(), "Run selected bindings");
        for (var s : selections)
            if (s.mode() == BillingMode.CHARGE)
                ContractChecks.require(limits.stream().anyMatch(l -> l.currency().equals(s.toolPrice() == null ? s.price().inputPerMillion().currency() : s.toolPrice().perCall().currency())), "Missing Run currency limit");
    }

    public Selection selection(DefinitionRef binding) {
        return selections.stream().filter(s -> s.binding().equals(binding)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Binding is outside Run budget selections"));
    }
}

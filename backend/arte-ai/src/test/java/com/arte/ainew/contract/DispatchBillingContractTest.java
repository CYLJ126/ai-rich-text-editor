package com.arte.ainew.contract;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;

import static org.junit.Assert.*;

/**
 * FREE 与既有收费命令兼容边界；不把零金额或缺失参数自动解释为免费。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 13:42 ✾
 */
public class DispatchBillingContractTest {
    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");
    private final ExecutionCommands.Version version = new ExecutionCommands.Version(owner, "invocation", 0);
    private final OutboxMessage claim = new OutboxMessage("message", "invocation", owner, OutboxMessage.Kind.DISPATCH,
            0, "worker", 1, Instant.now().plusSeconds(30));
    private final Money amount = new Money(BigDecimal.ZERO, Currency.getInstance("CNY"));
    private final DefinitionRef rate = new DefinitionRef("rate", "model", "v1");
    private final Duration lease = Duration.ofSeconds(30), retention = Duration.ofMinutes(5);

    @Test
    public void legacyConstructorAndAccessorsPreserveChargeEvenForZeroQuote() {
        var command = new DispatchPreparationStore.Command(version, claim, "attempt", lease, amount, rate, retention);
        assertEquals(new DispatchPreparationStore.Charge(amount, rate, retention), command.billingPlan());
        assertEquals(DispatchPreparationStore.BillingMode.CHARGE, command.billingPlan().mode());
        assertEquals(amount, command.amount());
        assertEquals(rate, command.rate());
        assertEquals(retention, command.retention());
        assertNotEquals(command.billingPlan(), new DispatchPreparationStore.Free());
    }

    @Test
    public void missingChargeParametersNeverFallBackToFree() {
        assertThrows(NullPointerException.class, () -> new DispatchPreparationStore.Charge(null, rate, retention));
        assertThrows(NullPointerException.class, () -> new DispatchPreparationStore.Charge(amount, null, retention));
        assertThrows(NullPointerException.class, () -> new DispatchPreparationStore.Charge(amount, rate, null));
        assertThrows(NullPointerException.class, () -> new DispatchPreparationStore.Command(version, claim, "attempt", lease, null));
    }

    @Test
    public void chargeRetainsExistingRateAndRetentionValidation() {
        assertThrows(IllegalArgumentException.class, () -> new DispatchPreparationStore.Charge(amount,
                new DefinitionRef("tool", "tool", "v1"), retention));
        assertThrows(IllegalArgumentException.class, () -> new DispatchPreparationStore.Charge(amount, rate, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DispatchPreparationStore.Charge(amount, rate, Duration.ofDays(31)));
    }

    @Test
    public void freeCommandStillRequiresMatchingOwnerAndInvocation() {
        var free = new DispatchPreparationStore.Free();
        assertThrows(IllegalArgumentException.class, () -> new DispatchPreparationStore.Command(
                new ExecutionCommands.Version(owner, "other", 0), claim, "attempt", lease, free));
        assertThrows(IllegalArgumentException.class, () -> new DispatchPreparationStore.Command(new ExecutionCommands.Version(
                new ExecutionOwner("tenant", "workspace", "other"), "invocation", 0), claim, "attempt", lease, free));
    }
}

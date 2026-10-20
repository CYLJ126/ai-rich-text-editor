package com.arte.ainew.tool.chat;

import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.tool.ToolChatBudget;
import com.arte.ainew.pojo.tool.ToolChatBudgetPlan;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.serialization.ToolChatRunDigests;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import static org.junit.Assert.*;

public class ToolChatBudgetContractTest {

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    public void budgetedSnapshotUsesV2AndDowngradeOrMissingPlanIsRejected() {
        try (var f = new ToolChatBudgetStoreTest.Rig()) {
            var bytes = f.r.codec.encode(f.s);
            assertEquals(2, json.readTree(bytes).path("schemaVersion").asInt());
            assertEquals(f.s, f.r.codec.decode(bytes, ToolChatRunSnapshot.class));
            assertThrows(IllegalArgumentException.class, () -> f.r.codec.decode(bytes.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), ToolChatRunSnapshot.class));
            var root = (ObjectNode) json.readTree(bytes);
            ((ObjectNode) root.path("body")).remove("budgetPlan");
            assertThrows(IllegalArgumentException.class, () -> f.r.codec.decode(json.writeValueAsString(root), ToolChatRunSnapshot.class));
            assertThrows(IllegalArgumentException.class, () -> f.r.codec.decode(bytes.replace("\"schemaVersion\":2", "\"schemaVersion\":2.5"), ToolChatRunSnapshot.class));
        }
    }

    @Test
    public void legacySnapshotRetainsV1BytesAndCannotPretendToContainBudget() {
        try (var f = new ToolChatRunStoreTest.Rig()) {
            var old = f.input("run", "legacy");
            var bytes = f.codec.encode(old);
            assertEquals(1, json.readTree(bytes).path("schemaVersion").asInt());
            assertFalse(json.readTree(bytes).path("body").has("budgetPlan"));
            assertEquals(old, f.codec.decode(bytes, ToolChatRunSnapshot.class));
            var root = (ObjectNode) json.readTree(bytes);
            ((ObjectNode) root.path("body")).putNull("budgetPlan");
            assertThrows(IllegalArgumentException.class, () -> f.codec.decode(json.writeValueAsString(root), ToolChatRunSnapshot.class));
        }
    }

    @Test
    public void planOrderingIsStableButDifferentLimitsAccountsOrPricesChangeIntent() {
        try (var f = new ToolChatBudgetStoreTest.Rig()) {
            var limits = new ArrayList<>(f.s.budgetPlan().limits());
            var selections = new ArrayList<>(f.s.budgetPlan().selections());
            Collections.reverse(limits);
            Collections.reverse(selections);
            var plan = new ToolChatBudgetPlan(limits, selections);
            assertEquals(f.s.budgetPlan(), plan);
            var changed = new ToolChatBudgetPlan(List.of(ToolChatBudgetStoreTest.money("9")), selections);
            var s = f.s;
            var other = new ToolChatRunSnapshot(s.context(), s.initialContext(), s.model(), s.generation(), s.tools(), s.limits(), s.receivedAt(), changed);
            assertNotEquals(ToolChatRunDigests.submission(s), ToolChatRunDigests.submission(other));
            assertEquals(com.arte.ainew.pojo.execution.StoreOutcome.Code.IDEMPOTENCY_CONFLICT,
                    f.r.store.accept(other, ignored -> {
                    }).block(ToolChatBudgetStoreTest.WAIT).code());
            assertEquals(2, f.r.count("arte_ai_tool_chat_budget"));
            assertThrows(IllegalArgumentException.class, () -> new ToolChatBudgetPlan(List.of(limits.getFirst(), limits.getFirst()), selections));
        }
    }

    @Test
    public void corruptOrMissingBudgetProjectionCannotBeDisplayedAsZero() {
        try (var f = new ToolChatBudgetStoreTest.Rig()) {
            f.r.jdbc.update("DELETE FROM arte_ai_tool_chat_budget WHERE currency='CNY'");
            assertThrows(IllegalStateException.class, f::budget);
        }
    }

    @Test
    public void rootAcceptanceFailureCannotLeavePartialCurrencyBudgetsOrWork() {
        try (var f = new ToolChatBudgetStoreTest.Rig()) {
            var old = f.r.input("another", "another-key");
            var other = new ToolChatRunSnapshot(old.context(), old.initialContext(), old.model(), old.generation(), old.tools(), old.limits(), old.receivedAt(), f.s.budgetPlan());
            f.r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_budget ADD CONSTRAINT fail_new_budget CHECK (run_key <> '" + com.arte.ainew.serialization.CanonicalJson.key("another") + "')");
            assertThrows(RuntimeException.class, () -> f.r.store.accept(other, ignored -> {
            }).block(ToolChatBudgetStoreTest.WAIT));
            assertEquals(1, f.r.count("arte_ai_tool_chat_run"));
            assertEquals(1, f.r.count("arte_ai_tool_chat_run_work"));
            assertEquals(2, f.r.count("arte_ai_tool_chat_budget"));
        }
    }

    @Test
    public void budgetWireAmountsRemainDecimalStringsAndExposeNoAccountSelection() {
        var limit = new Money(new BigDecimal("0.123456789012345678"), Currency.getInstance("CNY"));
        var value = new ToolChatBudget("run", limit, new Money(BigDecimal.ZERO, limit.currency()), new Money(BigDecimal.ZERO, limit.currency()), false, 0);
        var wire = json.valueToTree(com.arte.ainew.web.response.ToolChatBudgetResponse.from(value));
        assertTrue(wire.path("limit").isString());
        assertEquals("0.123456789012345678", wire.path("limit").asString());
        assertFalse(wire.has("budgetRef"));
        assertFalse(wire.has("owner"));
        assertFalse(wire.has("rate"));
    }

}

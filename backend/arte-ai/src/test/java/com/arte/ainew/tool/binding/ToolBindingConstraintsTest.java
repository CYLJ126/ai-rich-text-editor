package com.arte.ainew.tool.binding;

import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.core.pojo.value.StructuredValue;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;

/**
 * 完整覆盖层的递归规则与不可放宽的策略交集。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public class ToolBindingConstraintsTest {
    @Test
    public void objectsMergeRecursivelyWhileArraysScalarsAndNullReplace() {
        var defaults = object("nested", object("a", text("A"), "b", text("B")), "array", new StructuredValue.ArrayValue(List.of(text("a"))), "null", text("old"));
        var layer = object("nested", object("a", text("new")), "array", new StructuredValue.ArrayValue(List.of(text("x"), text("y"))), "null", StructuredValue.NullValue.INSTANCE);
        var merged = ToolBindingConstraints.merge(defaults, layer);
        assertEquals(object("nested", object("a", text("new"), "b", text("B")), "array", layer.fields().get("array"), "null", StructuredValue.NullValue.INSTANCE), merged);
        assertEquals(defaults, ToolBindingConstraints.merge(defaults, object()));
        assertThrows(UnsupportedOperationException.class, () -> merged.fields().put("x", text("x")));
    }

    @Test
    public void replacementCanChangeObjectToScalarAndBack() {
        assertEquals(object("v", text("x")), ToolBindingConstraints.merge(object("v", object("a", text("a"))), object("v", text("x"))));
        assertEquals(object("v", object("b", text("b"))), ToolBindingConstraints.merge(object("v", text("x")), object("v", object("b", text("b")))));
    }

    @Test
    public void intersectionKeepsTheStrongestLimitsAndApproval() {
        var a = policy(30, 3, 1, 500, 200, ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
        var b = policy(20, 2, 2, 700, 100, ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL);
        var expected = policy(20, 2, 2, 500, 100, ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL);
        assertEquals(expected, ToolBindingConstraints.intersect(a, b));
        assertEquals(expected, ToolBindingConstraints.intersect(b, a));
        assertEquals(a, ToolBindingConstraints.intersect(a, null));
    }

    @Test
    public void incompatibleBackoffAndTimeoutFailInsteadOfExtendingTheDeadline() {
        var a = policy(2, 1, 0, 100, 100, ToolExecutionPolicy.ApprovalMode.NONE);
        var b = policy(30, 1, 3, 100, 100, ToolExecutionPolicy.ApprovalMode.NONE);
        assertThrows(IllegalArgumentException.class, () -> ToolBindingConstraints.intersect(a, b));
    }

    @Test
    public void configurationDigestIsStandardJsonAndIndependentOfMapOrder() {
        var value = object("b", StructuredValue.NullValue.INSTANCE, "a", text("中文"));
        assertEquals(com.arte.ainew.serialization.CanonicalJson.digest(Map.of("a", "中文", "b", "placeholder")),
                ToolCatalogDigests.configuration(object("b", text("placeholder"), "a", text("中文"))));
        assertEquals(ToolCatalogDigests.configuration(value), ToolCatalogDigests.configuration(object("a", text("中文"), "b", StructuredValue.NullValue.INSTANCE)));
        assertNotEquals(ToolCatalogDigests.configuration(value), ToolCatalogDigests.configuration(object("a", text("中文"))));
    }

    static ToolExecutionPolicy policy(int timeout, int attempts, int backoff, long in, long out, ToolExecutionPolicy.ApprovalMode approval) {
        return new ToolExecutionPolicy(Duration.ofSeconds(timeout), attempts, Duration.ofSeconds(backoff), in, out, approval);
    }
}

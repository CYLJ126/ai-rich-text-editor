package com.arte.ainew.serialization;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.tool.ToolChatCommands;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;

import java.util.TreeSet;

/**
 * 区分原输入意图、推进命令和载荷摘要；刷新身份／租约不改变逻辑命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public final class ToolChatRunDigests {

    private ToolChatRunDigests() {
    }

    public static String submission(ToolChatRunSnapshot s) {
        var c = s.initialContext();
        var fields = CanonicalJson.fields("format", s.budgetPlan() == null ? "tool-chat-run-v1" : "tool-chat-run-v2", "owner", ExecutionOwner.from(s.context()),
                "model", model(s.model()), "messages", c.messages(), "history", c.history(), "fragments", c.fragments(),
                "contextBudget", c.budget(), "inputTokens", c.inputTokens(), "estimated", c.estimatedTokens(), "tokenizer", c.tokenizerVersion(),
                "truncations", c.truncations(), "tools", tools(s.tools()), "generation", s.generation(), "limits", s.limits(),
                "scopeCeiling", new TreeSet<>(s.context().authorization().scopes()), "release", s.context().releaseRef());
        if (s.budgetPlan() == null) return CanonicalJson.digest(fields);
        return CanonicalJson.digest(CanonicalJson.fields("input", fields, "budgetPlan", s.budgetPlan()));
    }

    /**
     * Set.copyOf 的遍历顺序可随 JVM 改变；语义摘要只使用显式排序后的集合投影。
     */
    private static Object model(ResolvedBinding model) {
        var c = model.capability();
        return CanonicalJson.fields("binding", model.definition(), "target", model.target(), "window", model.contextWindowTokens(), "rate", model.rate(),
                "capability", c.definition(), "kind", c.kind(), "input", c.inputSchema(), "output", c.outputSchema(),
                "features", c.features().stream().map(Enum::name).sorted().toList(), "sideEffect", c.sideEffect(), "availability", c.availability());
    }

    private static Object tools(ToolSetSnapshot tools) {
        return tools.entries().stream().map(entry -> {
            var b = entry.binding();
            return CanonicalJson.fields("name", entry.modelName(), "binding", b.definition(), "revision", b.revision(), "owner", b.owner(),
                    "tool", b.tool().tool().definition(), "toolChecksum", ToolCatalogDigests.tool(b.tool().tool()), "provider", b.tool().provider(),
                    "target", b.tool().target(), "contract", b.contractDigest(), "configuration", ToolCatalogDigests.configuration(b.effectiveConfiguration()),
                    "configurationDigest", b.configurationDigest(), "bindingPolicy", b.effectivePolicy(), "policy", entry.policy(),
                    "origins", b.networkOrigins().stream().map(Object::toString).sorted().toList(), "secrets", b.secrets(),
                    "schema", entry.inputSchema().definition(), "schemaChecksum", entry.inputSchema().checksum(),
                    "schemaContent", ToolCatalogDigests.schema(entry.inputSchema().document()));
        }).toList();
    }

    public static String advance(ToolChatCommands.Advance c) {
        return CanonicalJson.digest(CanonicalJson.fields("format", "tool-chat-advance-v1", "runId", c.lease().runId(),
                "expectedVersion", c.expectedVersion(), "state", c.state(), "phase", c.phase(), "round", c.roundIndex(),
                "plans", c.plans(), "nextWakeAt", c.nextWakeAt(), "result", c.result(), "error", c.error()));
    }
}

package com.arte.app.ainew;

import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.SourceRef;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 固定 v1 资料快照格式；动作、执行账本和耐久工作共享，旧空快照继续兼容。 */
final class ResourceContextJson {
    private ResourceContextJson() { }
    static String encode(ResourceContextSnapshot snapshot) {
        if (snapshot == null) return null;
        ResourceContextValues.verify(snapshot);
        var o = new JsonObject();
        o.addProperty("format", "arte.resource.context.v1");
        o.addProperty("id", snapshot.snapshotId());
        var scope = snapshot.scope();
        o.addProperty("tenant", scope.tenantId()); o.addProperty("workspace", scope.workspaceId());
        o.addProperty("principalType", scope.principal().type().name()); o.addProperty("principal", scope.principal().principalId());
        o.addProperty("bindingType", snapshot.bindingRef().definitionType()); o.addProperty("bindingId", snapshot.bindingRef().definitionId());
        o.addProperty("bindingVersion", snapshot.bindingRef().version());
        o.add("messages", JsonParser.parseString(ChatJson.messages(snapshot.messages())));
        var fragments = new JsonArray();
        for (var fragment : snapshot.fragments()) {
            var f = new JsonObject();
            f.addProperty("citation", fragment.citationId());
            f.add("resource", JsonParser.parseString(ChatJson.resources(List.of(fragment.source().resource()))));
            f.addProperty("sourceCitation", fragment.source().citationId()); f.addProperty("content", fragment.content());
            f.addProperty("truncated", fragment.truncated()); f.addProperty("coverage", fragment.coverageDescription());
            fragments.add(f);
        }
        o.add("fragments", fragments);
        var b = snapshot.budget(); var budget = new JsonObject();
        budget.addProperty("bytes", b.inputByteLimit()); budget.addProperty("used", b.usedInputBytes()); budget.addProperty("output", b.outputTokenReserve());
        budget.addProperty("window", b.contextWindowTokens()); budget.addProperty("limit", b.inputTokenLimit());
        budget.addProperty("estimated", b.estimatedInputTokens()); budget.addProperty("safety", b.safetyTokenReserve()); budget.addProperty("estimator", b.estimatorVersion());
        o.add("budget", budget);
        o.addProperty("digest", snapshot.contentDigest()); o.addProperty("created", snapshot.createdAt().toString()); o.addProperty("expires", snapshot.expiresAt().toString());
        o.addProperty("selectionDigest", snapshot.selectionDigest());
        return o.toString();
    }

    static ResourceContextSnapshot decode(String json) {
        if (json == null) return null;
        var o = JsonParser.parseString(json).getAsJsonObject();
        if (!"arte.resource.context.v1".equals(value(o, "format"))) throw new IllegalArgumentException("unsupported resource context format");
        var fragments = new ArrayList<ContextFragment>();
        for (var element : o.getAsJsonArray("fragments")) {
            var f = element.getAsJsonObject();
            fragments.add(new ContextFragment(value(f, "citation"), new SourceRef(ChatJson.resources(f.get("resource").toString()).getFirst(), value(f, "sourceCitation")),
                    value(f, "content"), f.get("truncated").getAsBoolean(), value(f, "coverage")));
        }
        var b = o.getAsJsonObject("budget");
        var budget = new ContextBudget(integer(b, "bytes"), integer(b, "used"), integer(b, "output"), integer(b, "window"), integer(b, "limit"), integer(b, "estimated"), integer(b, "safety"), value(b, "estimator"));
        var snapshot = new ResourceContextSnapshot(value(o, "id"), new ExecutionScope(value(o, "tenant"), value(o, "workspace"),
                new PrincipalRef(value(o, "principal"), PrincipalType.valueOf(value(o, "principalType")))),
                new DefinitionRef(value(o, "bindingType"), value(o, "bindingId"), value(o, "bindingVersion")),
                ChatJson.messages(o.get("messages").toString()), fragments, budget, value(o, "digest"), value(o, "selectionDigest"), Instant.parse(value(o, "created")), Instant.parse(value(o, "expires")));
        ResourceContextValues.verify(snapshot);
        return snapshot;
    }
    private static String value(JsonObject o, String key) { return ModelJson.value(o, key); }
    private static int integer(JsonObject o, String key) { return o.get(key).getAsBigDecimal().intValueExact(); }
}

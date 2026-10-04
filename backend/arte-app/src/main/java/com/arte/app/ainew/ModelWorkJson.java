package com.arte.app.ainew;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.model.execution.InvocationRequest;
import com.arte.ai.model.execution.QueuedModelCall;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.base.model.execution.CancellationRef;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.google.gson.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * 明确的版本化文本工作格式，不使用 Java 类型标签或保存凭据。
 */
final class ModelWorkJson {
    static String encode(QueuedModelCall call) {
        var request = call.request();
        if (!request.input().tools().isEmpty() || request.input().outputSchema() != null)
            throw new IllegalArgumentException("queued model supports text only");
        var context = request.context();
        var o = new JsonObject();
        o.addProperty("format", request.input().resourceContext() == null ? "arte.model.work.v1" : "arte.model.work.v2");
        if (request.input().resourceContext() != null) o.add("resourceContext", JsonParser.parseString(ResourceContextJson.encode(request.input().resourceContext())));
        o.add("capability", ref(request.capabilityRef()));
        o.add("binding", ref(request.bindingRef()));
        o.add("messages", JsonParser.parseString(ChatJson.messages(request.input().messages())));
        o.add("options", JsonParser.parseString(ChatJson.options(request.input().options())));
        o.addProperty("timeout", request.options().timeout().toString());
        o.addProperty("streaming", request.options().streaming());
        o.addProperty("tenant", context.scope().tenantId());
        o.addProperty("workspace", context.scope().workspaceId());
        o.addProperty("principal", context.scope().principal().principalId());
        o.addProperty("principalType", context.scope().principal().type().name());
        o.addProperty("trace", context.traceId());
        o.addProperty("parent", context.parentExecutionId());
        o.addProperty("deadline", context.deadline() == null ? null : context.deadline().toString());
        o.addProperty("executeBy", call.executeBy().toString());
        o.addProperty("cancellation", context.cancellation() == null ? null : context.cancellation().executionId());
        var scopes = new JsonArray();
        context.authorizationScopes().stream().sorted().forEach(scopes::add);
        o.add("scopes", scopes);
        o.add("resources", JsonParser.parseString(ChatJson.resources(Arrays.asList(call.consent()))));
        o.add("budget", resource(context.budgetRef()));
        o.add("release", resource(context.releaseRef()));
        if (context.idempotencyKey() != null) {
            var key = new JsonObject();
            key.addProperty("key", context.idempotencyKey().key());
            key.addProperty("operation", context.idempotencyKey().operation());
            key.addProperty("digest", context.idempotencyKey().requestDigest());
            o.add("idempotency", key);
        }
        o.addProperty("fingerprint", call.fingerprint());
        return o.toString();
    }

    static QueuedModelCall decode(String json) {
        var o = JsonParser.parseString(json).getAsJsonObject();
        if (!java.util.Set.of("arte.model.work.v1", "arte.model.work.v2").contains(value(o, "format")))
            throw new IllegalArgumentException("unsupported work format");
        var scopes = new HashSet<String>();
        o.getAsJsonArray("scopes").forEach(v -> scopes.add(v.getAsString()));
        var key = o.has("idempotency") ? o.getAsJsonObject("idempotency") : null;
        var context = new ExecutionContext(new ExecutionScope(value(o, "tenant"), value(o, "workspace"),
                new PrincipalRef(value(o, "principal"), PrincipalType.valueOf(value(o, "principalType")))),
                value(o, "trace"), value(o, "parent"), value(o, "deadline") == null ? null : Instant.parse(value(o, "deadline")),
                value(o, "cancellation") == null ? null : new CancellationRef(value(o, "cancellation")), scopes,
                resource(o.get("budget")), resource(o.get("release")), key == null ? null : new IdempotencyKey(value(key, "key"), value(key, "operation"), value(key, "digest")));
        var input = new GenerationRequest(ChatJson.messages(o.get("messages").toString()), ChatJson.options(o.get("options").toString()), List.of(), null,
                "arte.model.work.v2".equals(value(o, "format")) ? ResourceContextJson.decode(o.get("resourceContext").toString()) : null);
        return new QueuedModelCall(new InvocationRequest<>(ref(o.getAsJsonObject("capability")), ref(o.getAsJsonObject("binding")), input,
                new ExecutionOptions(Duration.parse(value(o, "timeout")), o.get("streaming").getAsBoolean()), context),
                ChatJson.resources(o.get("resources").toString()).getFirst(), value(o, "fingerprint"), Instant.parse(value(o, "executeBy")));
    }

    private static JsonObject ref(DefinitionRef ref) {
        var o = new JsonObject();
        o.addProperty("type", ref.definitionType());
        o.addProperty("id", ref.definitionId());
        o.addProperty("version", ref.version());
        return o;
    }

    private static DefinitionRef ref(JsonObject o) {
        return new DefinitionRef(value(o, "type"), value(o, "id"), value(o, "version"));
    }

    private static JsonElement resource(ResourceRef ref) {
        return ref == null ? JsonNull.INSTANCE : JsonParser.parseString(ChatJson.resources(List.of(ref)));
    }

    private static ResourceRef resource(JsonElement json) {
        return json == null || json.isJsonNull() ? null : ChatJson.resources(json.toString()).getFirst();
    }

    private static String value(JsonObject o, String key) {
        return ModelJson.value(o, key);
    }

    private ModelWorkJson() {
    }
}

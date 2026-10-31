package com.arte.app.platform.persistence;

import com.arte.ainew.serialization.ResolvedBindingJson;
import com.arte.app.platform.ai.AiControlData;
import com.arte.core.pojo.platform.CommandIdentity;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.StoredCommandReceipt;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;
import com.arte.core.pojo.workspace.WorkspaceViews;
import com.arte.core.serialize.SerializerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 固定模式的 Json 序列化和反序列化，无默认类型，无类名。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:03 ✾
 */
public final class PlatformJson {

    private final JsonMapper mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty()
            .rebuild()
            .addModule(ResolvedBindingJson.module(false))
            .addModule(ResourceAclJson.module())
            .disable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Map<String, Class<?>> RESULTS =
            Map.ofEntries(
                    Map.entry("personal:view:v1", WorkspaceViews.PersonalWorkspaceView.class),
                    Map.entry("workspace:view:v1", WorkspaceViews.WorkspaceView.class),
                    Map.entry("preference:view:v1", WorkspaceViews.PreferenceView.class),
                    Map.entry("tenant:policy:v1", TenantPolicy.class),
                    Map.entry("workspace:policy:v1", WorkspacePolicy.class),
                    Map.entry("workspace:ai:view:v1", AiControlData.WorkspaceView.class),
                    Map.entry("ai:catalog:view:v1", AiControlData.CatalogView.class),
                    Map.entry("resource:access:v1", com.arte.core.pojo.access.ResourceAccessViews.AccessView.class),
                    Map.entry("resource:grant:v1", com.arte.core.pojo.access.ResourceAccessViews.GrantResult.class),
                    Map.entry("resource:void:v1", Void.class),
                    Map.entry("model:resource:v1", com.arte.ainew.pojo.model.ModelResource.class),
                    Map.entry("model:version:v1", com.arte.ainew.pojo.model.ModelResourceVersion.class),
                    Map.entry("model:acl:scope:v1", com.arte.ainew.pojo.model.ModelAclScope.class));

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public Class<?> resultType(String schema) {
        Class<?> type = RESULTS.get(schema);
        if (type == null) throw new IllegalStateException("Unsupported command result schema");
        return type;
    }

    public void validate(StoredCommandReceipt stored) {
        Class<?> type = resultType(stored.resultSchema());
        if (type == Void.class ? stored.receipt().value() != null : !type.isInstance(stored.receipt().value()))
            throw new IllegalArgumentException("Receipt does not match registered result schema");
    }

    public StoredCommandReceipt receipt(String json) {
        var tree = mapper.readTree(json);
        String schema = tree.get("resultSchema").asString();
        var identity = mapper.treeToValue(tree.get("identity"), CommandIdentity.class);
        var r = tree.get("receipt");
        var result = resultType(schema) == Void.class ? null : mapper.treeToValue(r.get("value"), resultType(schema));
        if (resultType(schema) == Void.class && !r.get("value").isNull())
            throw new IllegalArgumentException("Invalid void result");
        var receipt = new CommandReceipt<>(
                r.get("operationId").asString(),
                mapper.treeToValue(
                        r.get("status"),
                        com.arte.core.enums.platform.CommandStatusEnum.class),
                mapper.treeToValue(
                        r.get("delivery"),
                        com.arte.core.enums.platform.CommandDeliveryEnum.class),
                result,
                r.get("resultVersion").asLong(),
                mapper.treeToValue(r.get("acceptedAt"), java.time.Instant.class),
                r.get("completedAt").isNull()
                        ? null
                        : mapper.treeToValue(
                        r.get("completedAt"), java.time.Instant.class));
        return new StoredCommandReceipt(
                identity,
                tree.get("requestDigest").asString(),
                schema,
                receipt,
                tree.get("version").asLong());
    }
}

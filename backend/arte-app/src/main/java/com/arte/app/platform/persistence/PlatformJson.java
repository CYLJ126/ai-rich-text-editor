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
            .disable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Map<String, Class<?>> RESULTS =
            Map.of(
                    "personal:view:v1", WorkspaceViews.PersonalWorkspaceView.class,
                    "workspace:view:v1", WorkspaceViews.WorkspaceView.class,
                    "preference:view:v1", WorkspaceViews.PreferenceView.class,
                    "tenant:policy:v1", TenantPolicy.class,
                    "workspace:policy:v1", WorkspacePolicy.class,
                    "workspace:ai:view:v1", AiControlData.WorkspaceView.class,
                    "ai:catalog:view:v1", AiControlData.CatalogView.class
            );

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
        if (stored.receipt().value() == null
                || !resultType(stored.resultSchema()).isInstance(stored.receipt().value()))
            throw new IllegalArgumentException("Receipt does not match registered result schema");
    }

    public StoredCommandReceipt receipt(String json) {
        var tree = mapper.readTree(json);
        String schema = tree.get("resultSchema").asString();
        var identity = mapper.treeToValue(tree.get("identity"), CommandIdentity.class);
        var r = tree.get("receipt");
        var result = mapper.treeToValue(r.get("value"), resultType(schema));
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

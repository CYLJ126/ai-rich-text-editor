package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/** 固定版本的操作契约，发现不授予权限。可执行能力必须登记输入输出 Schema；Schema 可映射受信 Java 类型。 */
public record CapabilityDescriptor(DefinitionRef definition, Kind kind, DefinitionRef inputSchema,
                                   DefinitionRef outputSchema, Set<Feature> features,
                                   SideEffect sideEffect, Availability availability) implements Serializable {
    public enum Kind { GENERATION, EMBEDDING, MEDIA, TOOL, REMOTE_APPLICATION }
    public enum Feature { TEXT_INPUT, IMAGE_INPUT, AUDIO_INPUT, VIDEO_INPUT, STREAMING, TOOL_CALLS,
        STRUCTURED_OUTPUT, USAGE_REPORTING, ASYNC_TASK, TASK_QUERY, CANCEL }
    public enum SideEffect { READ_ONLY, RESOURCE_WRITE, EXTERNAL_EFFECT, UNKNOWN }
    public enum Availability { DISCOVERED, EXECUTABLE, DISABLED }

    public CapabilityDescriptor {
        Objects.requireNonNull(definition, "definition").requireType("capability");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(availability, "availability");
        features = ContractChecks.set(features, "features", Feature.values().length);
        if (inputSchema != null) { inputSchema.requireType("schema"); }
        if (outputSchema != null) { outputSchema.requireType("schema"); }
        ContractChecks.require(availability != Availability.EXECUTABLE || inputSchema != null && outputSchema != null,
                "Executable capability requires input and output schemas");
    }
}

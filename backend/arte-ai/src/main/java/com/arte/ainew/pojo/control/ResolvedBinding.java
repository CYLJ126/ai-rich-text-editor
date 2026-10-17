package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.io.Serializable;
import java.util.Objects;

/**
 * 服务端解析的固定绑定；target 是执行位置的唯一事实，不含地址、凭据或 SDK。
 * 对象存在不表示已通过当前授权；本地目标只适用于 TOOL，不需要伪造连接或模型窗口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record ResolvedBinding(DefinitionRef definition, CapabilityDescriptor capability,
                              ExecutionTarget target,
                              Long contextWindowTokens, DefinitionRef rate) implements Serializable {
    /**
     * 兼容既有 Java 调用及 arte.ai-new.bindings 的固定生成配置。
     * Spring 继续严格绑定原六个字段；新 TOOL 配置由后续独立控制面构造类型化目标。
     */
    @ConstructorBinding
    public ResolvedBinding(DefinitionRef definition, CapabilityDescriptor capability,
                           DefinitionRef connection, String remoteOperation,
                           Long contextWindowTokens, DefinitionRef rate) {
        this(definition, capability, new ExecutionTarget.Remote(connection, remoteOperation), contextWindowTokens, rate);
    }

    public ResolvedBinding {
        Objects.requireNonNull(definition, "definition").requireType("binding");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(target, "target");
        if (target instanceof ExecutionTarget.Local) {
            ContractChecks.require(capability.kind() == CapabilityDescriptor.Kind.TOOL && contextWindowTokens == null,
                    "Local target requires TOOL without a model context window");
        }
        if (contextWindowTokens != null) {
            ContractChecks.range(contextWindowTokens, "contextWindowTokens", 1, 10_000_000);
        }
        ContractChecks.require(capability.kind() != CapabilityDescriptor.Kind.GENERATION || contextWindowTokens != null,
                "Generation binding requires context capacity");
        if (rate != null) {
            rate.requireType("rate");
        }
    }

    /**
     * 供现有模型链路读取远端事实；本地目标明确拒绝，不以 null 或虚构连接兜底。
     */
    public ExecutionTarget.Remote requireRemoteTarget() {
        if (target instanceof ExecutionTarget.Remote remote) return remote;
        throw new IllegalStateException("Binding does not have a remote target");
    }

    public DefinitionRef connection() {
        return requireRemoteTarget().connection();
    }

    public String remoteOperation() {
        return requireRemoteTarget().operation();
    }
}

package com.arte.ainew.pojo.control;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.io.Serializable;
import java.util.Objects;

/**
 * 服务端解析的固定绑定
 * <p>
 * 服务端解析出的固定版本执行绑定，将“能力契约”与“具体执行目标”关联起来，并提供上下文容量和费率信息。
 * 例如，同一个文本生成能力可以绑定到不同远端模型；工具能力可以绑定到本地适配器或远端操作。执行链路据此选择目标、校验上下文容量及计费配置。
 * 它不携带实际地址或凭据，持有该对象也不代表已有执行权限。
 * <p>
 * target 是执行位置的唯一事实，不含地址、凭据或 SDK。
 * 对象存在不表示已通过当前授权；本地目标只适用于 TOOL，不需要伪造连接或模型窗口。
 *
 * @param definition          本绑定的固定版本引用，类型必须为 binding，用于精确定位绑定配置。
 * @param capability          本绑定关联的固定版本能力契约，描述能力类型、输入输出 Schema、特性、副作用及可用状态；不代表当前已获执行权限。
 * @param target              唯一的执行目标：Remote 保存受管连接引用及远端模型名或工具操作键，Local 保存本地工具适配器引用；本地目标仅支持 TOOL。
 * @param contextWindowTokens 模型上下文窗口的 Token 容量，用于上下文预算及容量校验；GENERATION 必填，本地目标必须为空，非空时范围为 1～10,000,000。
 * @param rate                可选的固定版本费率引用，非空时类型必须为 rate，用于关联计费规则；具体执行链路仍须校验费率、预算及计费资格，不能仅凭为空认定可免费执行。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record ResolvedBinding(DefinitionRef definition, CapabilityDescriptor capability,
                              ExecutionTarget target, Long contextWindowTokens,
                              DefinitionRef rate) implements Serializable {

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

package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 服务端解析的固定绑定；不含地址、凭据或 SDK。对象存在不表示已通过当前授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record ResolvedBinding(DefinitionRef definition, CapabilityDescriptor capability,
                              DefinitionRef connection, String remoteOperation,
                              Long contextWindowTokens, DefinitionRef rate) implements Serializable {
    public ResolvedBinding {
        Objects.requireNonNull(definition, "definition").requireType("binding");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(connection, "connection").requireType("connection");
        ContractChecks.id(remoteOperation, "remoteOperation");
        if (contextWindowTokens != null) {
            ContractChecks.range(contextWindowTokens, "contextWindowTokens", 1, 10_000_000);
        }
        ContractChecks.require(capability.kind() != CapabilityDescriptor.Kind.GENERATION || contextWindowTokens != null,
                "Generation binding requires context capacity");
        if (rate != null) {
            rate.requireType("rate");
        }
    }
}

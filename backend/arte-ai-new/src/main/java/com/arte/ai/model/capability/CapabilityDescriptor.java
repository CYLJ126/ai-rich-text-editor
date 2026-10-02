package com.arte.ai.model.capability;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.schema.SchemaRef;
import com.arte.base.validation.ContractChecks;
import java.util.Set;

/**
 * 固定版本能力契约；发现不表示获得权限，Schema 可留待具体协议补充。
 */
public record CapabilityDescriptor(DefinitionRef ref, CapabilityKind kind, SchemaRef inputSchema,
                                   SchemaRef outputSchema, Set<String> features, SideEffectKind sideEffectKind) {
    public CapabilityDescriptor {
        ref = ContractChecks.required(ref, "ref");
        kind = ContractChecks.required(kind, "kind");
        features = ContractChecks.identifiers(features, "features");
        sideEffectKind = ContractChecks.required(sideEffectKind, "sideEffectKind");
    }
}

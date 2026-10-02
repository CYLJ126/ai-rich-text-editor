package com.arte.ai.model.definition;
import com.arte.base.model.security.SecretRef;
import com.arte.base.validation.ContractChecks;
import java.net.URI;

/**
 * 地址是运维受控配置；网络运行时另核对出口，不接受请求体携带地址。
 */
public record ConnectionDefinition(DefinitionRef ref, String providerId, String protocol, URI endpoint,
                                   SecretRef secretRef, DefinitionStatus status) {
    public ConnectionDefinition {
        ref = ContractChecks.required(ref, "ref");
        providerId = ContractChecks.identifier(providerId, "providerId");
        protocol = ContractChecks.identifier(protocol, "protocol");
        endpoint = ContractChecks.required(endpoint, "endpoint");
        secretRef = ContractChecks.required(secretRef, "secretRef");
        status = ContractChecks.required(status, "status");
        if (endpoint.getHost() == null || !"https".equalsIgnoreCase(endpoint.getScheme()) && !"http".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
            throw new IllegalArgumentException("endpoint must be a controlled HTTP(S) URI without credentials or query");
    }
}

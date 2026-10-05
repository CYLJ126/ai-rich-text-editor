package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * 首批 HTTP 连接配置，仅来自受信控制面；发送时仍需检查停用、出口与 SecretRef 当前权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 13:09 ✾
 */
public record ConnectionDefinition(DefinitionRef definition, String providerId, DefinitionRef protocol,
                                   URI endpoint, DefinitionRef credential, State state,
                                   Duration connectTimeout, Duration responseTimeout,
                                   int maxResponseBytes) implements Serializable {
    public enum State {ENABLED, DISABLED}

    public ConnectionDefinition {
        Objects.requireNonNull(definition, "definition").requireType("connection");
        ContractChecks.id(providerId, "providerId");
        Objects.requireNonNull(protocol, "protocol").requireType("protocol");
        Objects.requireNonNull(endpoint, "endpoint");
        ContractChecks.require(("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                        && endpoint.getHost() != null && endpoint.getUserInfo() == null
                        && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null,
                "Endpoint must be HTTP(S) without credentials, query or fragment");
        if (credential != null) {
            credential.requireType("secret");
        }
        Objects.requireNonNull(state, "state");
        ContractChecks.require(!Objects.requireNonNull(connectTimeout, "connectTimeout").isNegative()
                && !connectTimeout.isZero(), "Connect timeout must be positive");
        ContractChecks.require(!Objects.requireNonNull(responseTimeout, "responseTimeout").isNegative()
                && !responseTimeout.isZero(), "Response timeout must be positive");
        ContractChecks.range(maxResponseBytes, "maxResponseBytes", 1, 32 * 1024 * 1024);
    }
}

package com.arte.base.security;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.security.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SecurityFixtures {

    static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SecurityFixtures() {
    }

    static ExecutionContext context(String tenant, String workspace, String principal) {
        return ExecutionContext.create(new ExecutionScope(tenant, workspace,
                new PrincipalRef(principal, PrincipalType.USER)), "trace-1", Set.of("task.read", "task.egress"));
    }

    static ExecutionContext context() {
        return context("tenant-1", "workspace-1", "user-1");
    }

    static AuthorizationRequest read() {
        return AuthorizationRequest.of(context(), ResourceRef.saved("article", "article-1", "rev-1"), CommonResourceAction.READ);
    }

    static PolicyDecision allow() {
        return PolicyDecision.allow(Map.of("resource-policy", "version-1"), NOW, NOW.plusSeconds(5));
    }

    static PolicyDecision deny() {
        return PolicyDecision.deny(Set.of("action_not_allowed"), Map.of("resource-policy", "version-2"), NOW);
    }

    static EgressDestination destination(String connectionVersion, String origin) {
        return new EgressDestination(ResourceRef.saved("connection", "connection-1", connectionVersion), URI.create(origin));
    }

    static EgressRequest egress() {
        return EgressRequest.of(context(),
                List.of(new SourceRef(ResourceRef.saved("article", "article-1", "rev-1"), "source-1")),
                destination("version-1", "https://example.test"), "ai.generate", "sha256:packet-1", null);
    }

    static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

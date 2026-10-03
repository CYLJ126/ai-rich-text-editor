package com.arte.app.ainew;

import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.model.execution.InvocationRequest;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.base.model.execution.AcceptedExecution;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.util.Set;

/**
 * 新 HTTP 入口的身份与明确外发同意组合；请求体不能提供 ExecutionContext、地址、凭据或报价。
 */
public final class NewModelCallService {
    private final InvocationCoordinator coordinator;
    private final ExecutionContextFactory contexts;
    private final EgressConsentService consents;
    private final ConfiguredModelDefinitions definitions;
    private final String application;

    public NewModelCallService(InvocationCoordinator coordinator, ExecutionContextFactory contexts, EgressConsentService consents, ConfiguredModelDefinitions definitions, String application) {
        this.coordinator = coordinator;
        this.contexts = contexts;
        this.consents = consents;
        this.definitions = definitions;
        this.application = application;
    }

    public AcceptedExecution generate(HttpServletRequest http, String tenant, String workspace, String key, GenerationRequest input, boolean externalTransferConfirmed) {
        com.arte.base.validation.ContractChecks.identifier(key, "idempotencyKey");
        if (key.length() > 128) throw new IllegalArgumentException("idempotencyKey is too long");
        if (key.startsWith("chat:") || key.startsWith("action:"))
            throw new IllegalArgumentException("reserved scene idempotency namespace");
        if (!externalTransferConfirmed)
            throw new AccessDeniedException("arte.ai.external_transfer_confirmation_required");
        var context = viewer(http, tenant, workspace);
        var request = new InvocationRequest<>(definitions.capabilityRef(), definitions.bindingRef(), input, new ExecutionOptions(Duration.ofSeconds(90), false), context);
        var prepared = coordinator.prepare(request);
        var consent = consents.confirm(http, coordinator.egressRequest(request, prepared, null));
        return coordinator.submitModel(request, consent, key);
    }

    public ExecutionContext viewer(HttpServletRequest http, String tenant, String workspace) {
        return contexts.create(http, tenant, workspace, application, definitions.bindingRef().definitionId(),
                Set.of(CommonResourceAction.AI_PROCESS.code(), CommonResourceAction.EGRESS.code()));
    }

    public InvocationCoordinator coordinator() {
        return coordinator;
    }
}

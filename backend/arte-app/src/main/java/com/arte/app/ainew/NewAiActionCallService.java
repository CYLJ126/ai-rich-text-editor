package com.arte.app.ainew;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.action.AiActionResult;
import com.arte.ai.model.execution.ModelEvent;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.spi.security.ModelConsentProvider;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * 身份和外发同意的组合边界；动作核心仅依赖 base。
 */
public final class NewAiActionCallService {
    private final AiActionService actions;
    private final ExecutionContextFactory contexts;
    private final EgressConsentService consents;
    private final ConfiguredModelDefinitions definitions;
    private final String application;
    private final TransactionTemplate consentWrites;

    public NewAiActionCallService(AiActionService actions, ExecutionContextFactory contexts, EgressConsentService consents,
                                  ConfiguredModelDefinitions definitions, String application, PlatformTransactionManager manager) {
        this.actions = actions;
        this.contexts = contexts;
        this.consents = consents;
        this.definitions = definitions;
        this.application = application;
        consentWrites = new TransactionTemplate(manager);
        consentWrites.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        consentWrites.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public AiActionResult submit(HttpServletRequest http, String tenant, String workspace, String action, String text,
                                 String requirements, ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return actions.submit(viewer(http, tenant, workspace, true), action, text, requirements, options, key, consent(http));
    }

    public AiActionResult regenerate(HttpServletRequest http, String tenant, String workspace, String id,
                                     ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return actions.regenerate(viewer(http, tenant, workspace, true), id, options, key, consent(http));
    }

    public AiActionResult find(HttpServletRequest http, String tenant, String workspace, String id) {
        return actions.find(viewer(http, tenant, workspace, false), id);
    }

    public CancellationStatus cancel(HttpServletRequest http, String tenant, String workspace, String id) {
        return actions.cancel(viewer(http, tenant, workspace, false), id);
    }

    public record Observation(ExecutionContext viewer, String actionId, String executionId) {
    }

    public Observation observe(HttpServletRequest http, String tenant, String workspace, String id) {
        var viewer = viewer(http, tenant, workspace, false);
        var result = actions.find(viewer, id);
        if (result.execution() == null) throw ChatValues.failure(CommonErrorCode.BUSY, "action-stream");
        return new Observation(viewer, id, result.execution().executionId());
    }

    public List<ExecutionEvent<ModelEvent>> events(Observation observation, long after, int limit) {
        var events = actions.events(observation.viewer(), observation.actionId(), after, limit);
        if (events.stream().anyMatch(event -> !observation.executionId().equals(event.executionId())))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "action-stream");
        return events;
    }

    private ExecutionContext viewer(HttpServletRequest http, String tenant, String workspace, boolean external) {
        var permissions = external ? Set.of(CommonResourceAction.AI_PROCESS.code(), CommonResourceAction.EGRESS.code())
                : Set.of(CommonResourceAction.AI_PROCESS.code());
        return contexts.create(http, tenant, workspace, application, definitions.bindingRef().definitionId(), permissions);
    }

    private ModelConsentProvider consent(HttpServletRequest http) {
        return prepared -> {
            try {
                return consentWrites.execute(tx -> consents.confirm(http, prepared));
            } catch (AccessDeniedException denied) {
                throw ChatValues.failure(CommonErrorCode.UNAUTHORIZED, "action-consent");
            }
        };
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw new AccessDeniedException("arte.ai.external_transfer_confirmation_required");
    }
}

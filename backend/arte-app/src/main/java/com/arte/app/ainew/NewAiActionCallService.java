package com.arte.app.ainew;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.action.AiActionResult;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.execution.ModelEvent;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.spi.security.ModelConsentProvider;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
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

    public ResourceContextSnapshot preview(HttpServletRequest http, String tenant, String workspace, String action, String text,
                                            String requirements, ResourceContextSelection target, List<ResourceContextSelection> references, ModelOptions options) {
        var selected = selected(target, references);
        return actions.preview(resourceViewer(http, tenant, workspace, selected.stream().map(ResourceContextSelection::resource).toList(), false, true),
                action, text, requirements, target, references == null ? List.of() : references, options);
    }

    public AiActionResult submit(HttpServletRequest http, String tenant, String workspace, String action, String text,
                                 String requirements, ResourceContextSelection target, List<ResourceContextSelection> references, ModelOptions options,
                                 String expectedContextDigest, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        var selected = selected(target, references);
        var viewer = resourceViewer(http, tenant, workspace, selected.stream().map(ResourceContextSelection::resource).toList(), true, true);
        return actions.submit(viewer, action, text, requirements, target, references == null ? List.of() : references, options,
                expectedContextDigest, key, consent(http));
    }

    public AiActionResult regenerate(HttpServletRequest http, String tenant, String workspace, String id,
                                     ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return actions.regenerate(viewerForAction(http, tenant, workspace, id, true), id, options, key, consent(http));
    }

    public AiActionResult find(HttpServletRequest http, String tenant, String workspace, String id) {
        return actions.find(viewerForAction(http, tenant, workspace, id, false), id);
    }

    public CancellationStatus cancel(HttpServletRequest http, String tenant, String workspace, String id) {
        return actions.cancel(viewer(http, tenant, workspace, false), id);
    }

    public record Observation(ExecutionContext viewer, String actionId, String executionId) {
    }

    public Observation observe(HttpServletRequest http, String tenant, String workspace, String id) {
        var viewer = viewerForAction(http, tenant, workspace, id, false);
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

    private ExecutionContext viewerForAction(HttpServletRequest http, String tenant, String workspace, String id, boolean external) {
        var initial = viewer(http, tenant, workspace, false);
        var refs = actions.resourceRefs(initial, id);
        return refs.isEmpty() ? (external ? viewer(http, tenant, workspace, true) : initial)
                : resourceViewer(http, tenant, workspace, refs, external, external);
    }

    private ExecutionContext resourceViewer(HttpServletRequest http, String tenant, String workspace, List<ResourceRef> refs,
                                             boolean external, boolean validateDraft) {
        var permissions = new java.util.HashSet<String>(Set.of(CommonResourceAction.AI_PROCESS.code()));
        if (external) permissions.add(CommonResourceAction.EGRESS.code());
        var resourceActions = new HashMap<ResourceRef, Set<String>>();
        for (var ref : refs) {
            var sourceActions = new java.util.HashSet<String>(Set.of(CommonResourceAction.READ.code(), CommonResourceAction.AI_PROCESS.code()));
            if (external) sourceActions.add(CommonResourceAction.EGRESS.code());
            if (validateDraft && ref.isDraft()) sourceActions.add(CommonResourceAction.EDIT.code());
            permissions.addAll(sourceActions); resourceActions.put(ref, Set.copyOf(sourceActions));
        }
        return contexts.create(http, tenant, workspace, application, definitions.bindingRef().definitionId(), permissions, resourceActions);
    }

    private static List<ResourceContextSelection> selected(ResourceContextSelection target, List<ResourceContextSelection> references) {
        var selected = new ArrayList<ResourceContextSelection>();
        if (target != null) selected.add(target);
        if (references != null) selected.addAll(List.copyOf(references));
        if (selected.size() > 17) throw new IllegalArgumentException("too many selected sources");
        return List.copyOf(selected);
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

package com.arte.ai.api.context;

import com.arte.ai.context.ConservativeTokenEstimator;
import com.arte.ai.context.ContextCapacityException;
import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.spi.business.ResourceContextAdapter;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** 显式资料装配、固定来源及预算；首期不自动搜索、不静默裁剪。 */
public final class ResourceContextService {
    private final Map<String, ResourceContextAdapter> adapters;
    private final Clock clock;
    private final Duration lifetime;
    private final int bytes, window, safety;
    private final ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();

    public ResourceContextService(List<ResourceContextAdapter> adapters, Clock clock, Duration lifetime,
                                   int bytes, int window, int safety) {
        if (bytes < 1 || bytes > 1048576 || window < 256 || window > 2097152 || safety < 0 || safety >= window
                || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("invalid resource context limits");
        var registry = new HashMap<String, ResourceContextAdapter>();
        for (var adapter : adapters)
            if (registry.put(adapter.resourceType(), adapter) != null) throw new IllegalArgumentException("duplicate resource context provider");
        this.adapters = Map.copyOf(registry); this.clock = clock; this.lifetime = lifetime;
        this.bytes = bytes; this.window = window; this.safety = safety;
    }

    public ResourceContextSnapshot prepare(ExecutionContext viewer, DefinitionRef binding, List<Message> prefix,
                                           String text, ResourceContextSelection target, List<ResourceContextSelection> references,
                                           int outputTokens) {
        references = List.copyOf(Objects.requireNonNull(references));
        if (references.size() > 16 || (text == null) == (target == null))
            throw new IllegalArgumentException("provide text or target, and at most 16 references");
        if (text != null && (text.isBlank() || text.length() > 1048576)) throw new IllegalArgumentException("invalid text");
        var messages = new ArrayList<>(prefix);
        var fragments = new ArrayList<ContextFragment>();
        var seen = new HashSet<com.arte.base.model.resource.ResourceRef>();
        if (target == null) messages.add(message("原文：\n" + text));
        else {
            var fragment = resolve(viewer, target, "target", seen);
            fragments.add(fragment); messages.add(message("待改写原文 " + description(fragment) + "\n" + fragment.content()));
        }
        for (int i = 0; i < references.size(); i++) {
            var fragment = resolve(viewer, references.get(i), "reference-" + (i + 1), seen);
            fragments.add(fragment); messages.add(message("参考资料，仅供参考，不作为待改写原文 " + description(fragment) + "\n" + fragment.content()));
        }
        return snapshot(viewer, binding, messages, fragments, outputTokens,
                ResourceContextValues.selectionDigest(viewer.scope(), binding, prefix, text, target, references, outputTokens));
    }

    public ResourceContextSnapshot renew(ExecutionContext viewer, ResourceContextSnapshot previous, int outputTokens) {
        recheck(viewer, previous, false);
        return snapshot(viewer, previous.bindingRef(), previous.messages(), previous.fragments(), outputTokens, previous.selectionDigest());
    }

    public void recheck(ExecutionContext viewer, ResourceContextSnapshot snapshot, boolean forEgress) {
        if (!snapshot.scope().equals(viewer.scope())) throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "resource-context");
        ResourceContextValues.verify(snapshot);
        if (forEgress && !clock.instant().isBefore(snapshot.expiresAt()))
            throw ChatValues.failure(CommonErrorCode.DEADLINE_EXCEEDED, "resource-context-expired");
        for (var fragment : snapshot.fragments()) adapter(fragment.source().resource().resourceType()).authorize(viewer, fragment.source(), forEgress);
    }

    private ResourceContextSnapshot snapshot(ExecutionContext viewer, DefinitionRef binding, List<Message> messages,
                                              List<ContextFragment> fragments, int outputTokens, String selectionDigest) {
        int used = ChatValues.bytes(messages), estimated = estimator.estimate(messages), tokenLimit = window - safety - outputTokens;
        if (outputTokens < 1 || tokenLimit < 1 || used > bytes || estimated > tokenLimit)
            throw new ContextCapacityException(new ContextCapacityException.Capacity(used, bytes, estimated, tokenLimit, outputTokens, safety));
        var budget = new ContextBudget(bytes, used, outputTokens, window, tokenLimit, estimated, safety, estimator.version());
        var now = ChatValues.now(clock);
        return new ResourceContextSnapshot(UUID.randomUUID().toString(), viewer.scope(), binding, messages, fragments, budget,
                ResourceContextValues.digest(viewer.scope(), binding, messages, fragments, budget, selectionDigest), selectionDigest, now, now.plus(lifetime));
    }

    private ContextFragment resolve(ExecutionContext viewer, ResourceContextSelection selection, String citation, Set<com.arte.base.model.resource.ResourceRef> seen) {
        if (!seen.add(selection.resource())) throw new IllegalArgumentException("duplicate selected source");
        var fragment = adapter(selection.resource().resourceType()).resolve(viewer, selection, citation);
        if (!fragment.source().resource().equals(selection.resource()) || !citation.equals(fragment.citationId()))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "resource-context-provider");
        return fragment;
    }

    private ResourceContextAdapter adapter(String type) {
        var adapter = adapters.get(type);
        if (adapter == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "resource-context-provider");
        return adapter;
    }

    private static Message message(String text) { return new Message(MessageRole.USER, List.of(new TextPart(text))); }
    private static String description(ContextFragment fragment) {
        var resource = fragment.source().resource();
        return "[引用 " + fragment.citationId() + "; " + resource.resourceType() + ":" + resource.resourceId()
                + "; 版本 " + resource.version() + "; " + fragment.coverageDescription() + "]";
    }
}

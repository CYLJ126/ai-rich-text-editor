package com.arte.ai.context;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.message.Message;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.identity.ExecutionScope;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 明确长度编码；引用和覆盖说明不能独立于固定正文被改写。 */
public final class ResourceContextValues {
    private ResourceContextValues() { }

    public static String textDigest(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(ExecutionScope scope, DefinitionRef binding, List<Message> messages,
                                List<ContextFragment> fragments, ContextBudget budget, String selectionDigest) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            field(out, "arte.resource.context.v1");
            field(out, selectionDigest);
            field(out, scope.tenantId()); field(out, scope.workspaceId());
            field(out, scope.principal().type().name()); field(out, scope.principal().principalId());
            field(out, ChatValues.context("resource-context", 1, binding, messages, List.of(), budget));
            out.writeInt(fragments.size());
            for (var fragment : fragments) {
                var resource = fragment.source().resource();
                for (String value : new String[]{fragment.citationId(), resource.resourceType(), resource.resourceId(),
                        resource.version(), resource.draftId(), resource.rangeRef(), resource.contentDigest(), fragment.source().citationId(),
                        fragment.content(), fragment.coverageDescription()}) field(out, value);
                out.writeBoolean(fragment.truncated());
            }
            return hash(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static void verify(ResourceContextSnapshot snapshot) {
        if (!snapshot.contentDigest().equals(digest(snapshot.scope(), snapshot.bindingRef(), snapshot.messages(), snapshot.fragments(), snapshot.budget(), snapshot.selectionDigest()))
                || ChatValues.bytes(snapshot.messages()) != snapshot.budget().usedInputBytes())
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "resource-context-digest");
        var budget = snapshot.budget();
        var estimator = new ConservativeTokenEstimator();
        if (!estimator.version().equals(budget.estimatorVersion()) || estimator.estimate(snapshot.messages()) != budget.estimatedInputTokens())
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "resource-context-budget");
    }

    public static String selectionDigest(ExecutionScope scope, DefinitionRef binding, List<Message> prefix, String text,
                                          ResourceContextSelection target, List<ResourceContextSelection> references, int outputTokens) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            field(out, "arte.resource.selection.v1");
            field(out, scope.tenantId()); field(out, scope.workspaceId()); field(out, scope.principal().type().name()); field(out, scope.principal().principalId());
            field(out, binding.definitionType()); field(out, binding.definitionId()); field(out, binding.version());
            field(out, ChatValues.submission("resource-prefix", 1, null, prefix, new com.arte.ai.model.generation.ModelOptions(null, outputTokens)));
            field(out, text); selection(out, target); out.writeInt(references.size());
            for (var reference : references) selection(out, reference);
            return hash(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void selection(DataOutputStream out, ResourceContextSelection selection) throws IOException {
        out.writeBoolean(selection != null);
        if (selection == null) return;
        var r = selection.resource();
        for (String value : new String[]{r.resourceType(), r.resourceId(), r.version(), r.draftId(), r.rangeRef(), r.contentDigest(), selection.draftText()}) field(out, value);
    }

    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

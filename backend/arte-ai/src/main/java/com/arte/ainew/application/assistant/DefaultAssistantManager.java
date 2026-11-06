package com.arte.ainew.application.assistant;

import com.arte.ainew.api.control.AssistantManager;
import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.ainew.spi.assistant.AssistantStore;
import com.arte.ainew.spi.assistant.AssistantTrialVerifier;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.application.workspace.WorkspaceMutationEffects;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;
import static com.arte.core.enums.platform.PlatformErrorCodeEnum.*;

public final class DefaultAssistantManager implements AssistantManager {

    public static final String RECEIPT_SCHEMA = "assistant:receipt:v1";
    private static final int SCAN_SIZE = 100;
    private final AssistantStore store;
    private final PublicTransactionRunner tx;
    private final WorkspaceGovernanceLocks locks;
    private final AuthorizationService authorization;
    private final ResourceRegistrationService registration;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final AssistantTrialVerifier trials;
    private final Clock clock;

    public DefaultAssistantManager(AssistantStore store, PublicTransactionRunner tx, WorkspaceGovernanceLocks locks,
                                   AuthorizationService authorization, ResourceRegistrationService registration,
                                   IdentityProvider identity, CommandReceiptStore receipts, PublicOutboxStore outbox,
                                   AuditSink audit, PlatformIdGenerator ids, Clock clock, AssistantTrialVerifier trials) {
        this.store = store;
        this.tx = tx;
        this.locks = locks;
        this.authorization = authorization;
        this.registration = registration;
        this.commands = new DefaultCommandExecutor(tx, new CurrentPrincipalResolver(identity), receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        this.clock = clock;
        this.trials = trials;
    }

    @Override
    public CommandReceipt<Receipt> create(TrustedActor actor, Scope scope, Content content, CommandMeta meta) {
        validate(content, false);
        return write(actor, scope, meta, "create", ASSISTANT_READ, fields(content),
                operation -> create(actor, scope, content, meta, operation));
    }

    @Override
    public CommandReceipt<Receipt> copy(TrustedActor actor, Scope scope, String sourceId, Long sourceVersion,
                                        Long sourceDraftRevision, CommandMeta meta) {
        id(sourceId);
        check((sourceVersion == null) != (sourceDraftRevision == null), INVALID_ARGUMENT);
        long version = sourceVersion == null ? sourceDraftRevision : sourceVersion;
        check(version > 0, INVALID_ARGUMENT);
        return write(actor, scope, meta, "copy", ASSISTANT_READ,
                List.of(sourceId, sourceVersion == null ? "draft" : "release", Long.toString(version)), operation -> {
                    var source = current(scope, sourceId);
                    require(actor, source, ASSISTANT_READ);
                    Content content;
                    if (sourceVersion == null) {
                        var draft = draft(source, true);
                        check(draft.revision() == version, VERSION_CONFLICT);
                        content = draft.content();
                    } else {
                        content = release(source, version).content();
                    }
                    return create(actor, scope, content, meta, operation);
                });
    }

    private DefaultCommandExecutor.Result<Receipt> create(TrustedActor actor, Scope scope, Content content, CommandMeta meta, String operation) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, ASSISTANT_CREATE, "assistant-create"));
        var now = PlatformTimes.businessNow(clock);
        String subject = actor.principal().subjectId();
        var resource = new Resource(UUID.randomUUID().toString(), scope, UUID.randomUUID().toString(),
                State.DRAFT, null, 0, subject, now, subject, now);
        var draft = new Draft(resource.id(), 1, content, subject, now);
        store.insert(resource, draft);
        registration.registerCreated(actor, resource.descriptor(), new CommandMeta("assistant-register:" + operation, meta.traceId()));
        return result(actor, resource, draft, operation, "created");
    }

    @Override
    public CommandReceipt<Receipt> saveDraft(TrustedActor actor, Scope scope, String id, long expectedRevision,
                                             Content content, CommandMeta meta) {
        id(id);
        check(expectedRevision > 0, INVALID_ARGUMENT);
        validate(content, false);
        var fields = new ArrayList<>(List.of(id, Long.toString(expectedRevision)));
        fields.addAll(fields(content));
        return write(actor, scope, meta, "save-draft", ASSISTANT_EDIT, fields, operation -> {
            var resource = current(scope, id);
            require(actor, resource, ASSISTANT_EDIT);
            check(resource.state() != State.DISABLED, ACCESS_DENIED);
            var old = draft(resource, true);
            check(old.revision() == expectedRevision, VERSION_CONFLICT);
            if (old.content().equals(content)) {
                return new DefaultCommandExecutor.Result<>(receipt(resource, old), resource.version());
            }
            var next = advance(actor, resource, resource.state(), resource.releaseVersion());
            var draft = new Draft(id, Math.incrementExact(old.revision()), content, next.updatedBy(), next.updatedAt());
            check(store.replaceDraft(draft, expectedRevision), VERSION_CONFLICT);
            check(store.replaceResource(next, resource.version()), VERSION_CONFLICT);
            return result(actor, next, draft, operation, "draft-saved");
        });
    }

    @Override
    public CommandReceipt<Receipt> publish(TrustedActor actor, Scope scope, String id, long expectedVersion,
                                           long expectedRevision, String trialId, CommandMeta meta) {
        id(id);
        id(trialId);
        check(expectedVersion >= 0 && expectedRevision > 0, INVALID_ARGUMENT);
        return write(actor, scope, meta, "publish", ASSISTANT_PUBLISH,
                List.of(id, Long.toString(expectedVersion), Long.toString(expectedRevision), trialId), operation -> {
                    var resource = current(scope, id);
                    require(actor, resource, ASSISTANT_PUBLISH);
                    check(resource.state() != State.DISABLED, ACCESS_DENIED);
                    var draft = draft(resource, true);
                    check(resource.version() == expectedVersion && draft.revision() == expectedRevision, VERSION_CONFLICT);
                    requireComplete(draft.content());
                    check(trials != null, CAPABILITY_NOT_ENABLED);
                    if (resource.releaseVersion() != null) {
                        check(release(resource, resource.releaseVersion()).draftRevision() < expectedRevision, VERSION_CONFLICT);
                    }
                    var trial = store.trial(id, trialId).orElseThrow(() -> error(NOT_FOUND));
                    String digest = contentDigest(draft.content());
                    check(trial.draftRevision() == expectedRevision && trial.contentDigest().equals(digest), VERSION_CONFLICT);
                    trials.requireSucceeded(scope, trial);
                    long version = resource.releaseVersion() == null ? 1 : Math.incrementExact(resource.releaseVersion());
                    var next = advance(actor, resource, State.ACTIVE, version);
                    store.insertRelease(new Release(id, version, expectedRevision, trialId, digest, draft.content(),
                            next.updatedBy(), next.updatedAt()));
                    check(store.replaceResource(next, expectedVersion), VERSION_CONFLICT);
                    return result(actor, next, draft, operation, "published");
                });
    }

    @Override
    public CommandReceipt<Receipt> disable(TrustedActor actor, Scope scope, String id, long expectedVersion, CommandMeta meta) {
        id(id);
        check(expectedVersion >= 0, INVALID_ARGUMENT);
        return write(actor, scope, meta, "disable", ASSISTANT_DISABLE, List.of(id, Long.toString(expectedVersion)), operation -> {
            var resource = current(scope, id);
            require(actor, resource, ASSISTANT_DISABLE);
            check(resource.version() == expectedVersion, VERSION_CONFLICT);
            var draft = draft(resource, true);
            if (resource.state() == State.DISABLED) {
                return new DefaultCommandExecutor.Result<>(receipt(resource, draft), resource.version());
            }
            var next = advance(actor, resource, State.DISABLED, resource.releaseVersion());
            check(store.replaceResource(next, expectedVersion), VERSION_CONFLICT);
            return result(actor, next, draft, operation, "disabled");
        });
    }

    @Override
    public Detail get(TrustedActor actor, Scope scope, String id) {
        return tx.required(() -> {
            lock(actor, scope);
            var resource = current(scope, id);
            require(actor, resource, ASSISTANT_READ);
            return new Detail(resource, draft(resource, true));
        });
    }

    @Override
    public Page<Managed> managed(TrustedActor actor, Scope scope, String afterId, String name, int limit) {
        return directory(actor, scope, afterId, name, limit, ASSISTANT_READ, resource -> {
            var draft = draft(resource, false);
            return new Managed(resource, draft.content().displayName(), draft.revision());
        }, Managed::displayName);
    }

    @Override
    public Page<Summary> usable(TrustedActor actor, Scope scope, String afterId, String name, int limit) {
        return directory(actor, scope, afterId, name, limit, ASSISTANT_USE, resource -> {
            var release = release(resource, resource.releaseVersion());
            var content = release.content();
            return new Summary(resource.id(), content.displayName(), content.description(), content.examplePrompts(),
                    resource.profile(release.version()));
        }, Summary::displayName);
    }

    private <T> Page<T> directory(TrustedActor actor, Scope scope, String afterId, String name, int limit, Action action,
                                  Function<Resource, T> project, Function<T, String> displayName) {
        pageSize(limit);
        if (afterId != null) id(afterId);
        String query = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        check(query.codePointCount(0, query.length()) <= 80, INVALID_ARGUMENT);
        authorization.require(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "assistant-list"));
        var items = new ArrayList<T>();
        String cursor = afterId;
        String lastVisible = null;
        while (true) {
            var candidates = store.scan(scope, cursor, ASSISTANT_USE.equals(action), SCAN_SIZE);
            var decisions = authorization.batchCheck(actor, candidates.stream().map(value -> request(value, action)).toList());
            for (int index = 0; index < candidates.size(); index++) {
                if (!decisions.get(index).allowed()) continue;
                var candidate = candidates.get(index);
                T value = project.apply(candidate);
                if (!displayName.apply(value).toLowerCase(Locale.ROOT).contains(query)) continue;
                if (items.size() == limit) return new Page<>(items, lastVisible);
                items.add(value);
                lastVisible = candidate.id();
            }
            if (candidates.size() < SCAN_SIZE) return new Page<>(items, null);
            cursor = candidates.getLast().id();
        }
    }

    @Override
    public Page<Release> versions(TrustedActor actor, Scope scope, String id, long afterVersion, int limit) {
        pageSize(limit);
        check(afterVersion >= 0, INVALID_ARGUMENT);
        return tx.required(() -> {
            lock(actor, scope);
            var resource = current(scope, id);
            require(actor, resource, ASSISTANT_READ);
            var versions = store.versions(id, afterVersion, limit + 1);
            if (versions.size() <= limit) return new Page<>(versions, null);
            return new Page<>(versions.subList(0, limit), Long.toString(versions.get(limit - 1).version()));
        });
    }

    @Override
    public Release resolvePublished(TrustedActor actor, Scope scope, DefinitionRef profile, boolean newConversation) {
        check(profile != null && "chat-profile".equals(profile.type()), INVALID_ARGUMENT);
        id(profile.id());
        long version = parseVersion(profile.version());
        check(version > 0, INVALID_ARGUMENT);
        return tx.required(() -> {
            lock(actor, scope);
            var resource = store.findByProfile(scope, profile.id(), true).orElseThrow(() -> error(NOT_FOUND));
            require(actor, resource, ASSISTANT_USE);
            check(resource.state() == State.ACTIVE, ACCESS_DENIED);
            if (newConversation) check(Objects.equals(resource.releaseVersion(), version), VERSION_CONFLICT);
            return release(resource, version);
        });
    }

    private CommandReceipt<Receipt> write(TrustedActor actor, Scope scope, CommandMeta meta, String action,
                                          Action replayAction, List<String> fields,
                                          Function<String, DefaultCommandExecutor.Result<Receipt>> work) {
        return commands.execute(actor, meta, new CommandScope(scope.tenantId(), scope.workspaceId()), "assistant:" + action,
                RECEIPT_SCHEMA, Receipt.class, fields, () -> lock(actor, scope),
                value -> require(actor, current(scope, value.assistantId()), replayAction), work);
    }

    private void lock(TrustedActor actor, Scope scope) {
        locks.scopes(actor.principal().subjectId(), List.of(scope));
    }

    private void require(TrustedActor actor, Resource resource, Action action) {
        authorization.requireInCurrentTransaction(actor, request(resource, action));
    }

    private static AccessRequest request(Resource resource, Action action) {
        return new AccessRequest(resource.scope(), resource.ref(), action, "assistant-management");
    }

    private Resource current(Scope scope, String id) {
        id(id);
        return store.find(new ResourceRef(scope.tenantId(), ASSISTANT, id), true)
                .filter(value -> value.scope().equals(scope)).orElseThrow(() -> error(NOT_FOUND));
    }

    private Draft draft(Resource resource, boolean current) {
        return store.draft(resource.id(), current).orElseThrow(() -> error(AUTHORITY_UNAVAILABLE));
    }

    private Release release(Resource resource, long version) {
        return store.release(resource.id(), version).orElseThrow(() -> error(NOT_FOUND));
    }

    private Resource advance(TrustedActor actor, Resource old, State state, Long releaseVersion) {
        return new Resource(old.id(), old.scope(), old.profileId(), state, releaseVersion, Math.incrementExact(old.version()),
                old.createdBy(), old.createdAt(), actor.principal().subjectId(), PlatformTimes.notBefore(clock, old.updatedAt()));
    }

    private DefaultCommandExecutor.Result<Receipt> result(TrustedActor actor, Resource resource, Draft draft, String operation, String event) {
        var scope = resource.scope();
        effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "assistant:" + event, operation,
                resource.version(), Map.of("assistantId", resource.id(), "draftRevision", Long.toString(draft.revision())));
        return new DefaultCommandExecutor.Result<>(receipt(resource, draft), resource.version());
    }

    private static Receipt receipt(Resource resource, Draft draft) {
        return new Receipt(resource.id(), resource.version(), draft.revision(),
                resource.releaseVersion() == null ? null : resource.profile(resource.releaseVersion()));
    }

    public static String contentDigest(Content content) {
        return DefaultCommandExecutor.digest(fields(content));
    }

    private static List<String> fields(Content content) {
        var values = new ArrayList<>(List.of(content.displayName(), content.description(), content.systemInstruction(), content.expectedOutput()));
        values.addAll(content.examplePrompts());
        return values;
    }

    public static void requireComplete(Content content) {
        validate(content, true);
    }

    private static void validate(Content content, boolean complete) {
        check(content != null, INVALID_ARGUMENT);
        text(content.displayName(), 80, complete);
        text(content.description(), 500, complete);
        text(content.expectedOutput(), 1000, complete);
        check(content.systemInstruction().getBytes(StandardCharsets.UTF_8).length <= 8192
                && (!complete || !content.systemInstruction().isEmpty()), INVALID_ARGUMENT);
        check(content.examplePrompts().size() <= 5 && (!complete || !content.examplePrompts().isEmpty()), INVALID_ARGUMENT);
        content.examplePrompts().forEach(value -> text(value, 500, complete));
    }

    private static void text(String text, int max, boolean required) {
        check(text.codePointCount(0, text.length()) <= max && (!required || !text.isEmpty()), INVALID_ARGUMENT);
    }

    private static void id(String id) {
        check(id != null && id.length() == 36, INVALID_ARGUMENT);
        try {
            check(UUID.fromString(id).toString().equals(id), INVALID_ARGUMENT);
        } catch (IllegalArgumentException exception) {
            throw error(INVALID_ARGUMENT);
        }
    }

    private static void pageSize(int size) {
        check(size > 0 && size <= SCAN_SIZE, INVALID_ARGUMENT);
    }

    public static long parseVersion(String version) {
        try {
            long value = Long.parseLong(version);
            check(Long.toString(value).equals(version), INVALID_ARGUMENT);
            return value;
        } catch (NumberFormatException exception) {
            throw error(INVALID_ARGUMENT);
        }
    }

    private static void check(boolean condition, PlatformErrorCodeEnum code) {
        if (!condition) throw error(code);
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "assistant-management");
    }
}

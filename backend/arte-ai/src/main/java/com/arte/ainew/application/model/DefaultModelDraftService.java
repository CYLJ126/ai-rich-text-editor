package com.arte.ainew.application.model;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.pojo.model.ModelDrafts.*;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver.BearerCredential;
import com.arte.ainew.spi.model.ModelConnectionTemplates;
import com.arte.ainew.spi.model.ModelCredentialCipher;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.core.api.access.AuthorizationService;
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
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;

/**
 * 个人模型配置准备应用服务，协调稳定模型身份、公共资源授权、草稿、个人凭据及连接验证状态。
 * <p>
 * 所有数据库操作沿用公共治理取锁顺序，读取当前身份、成员、政策和资源事实；模型读取、编辑及验证分别校验相应动作。
 * 凭据维护另核验原责任主体，退出空间后仅保留有效账号撤销本人凭据的受限路径，不恢复资源访问。
 * 幂等写命令通过 DefaultCommandExecutor 将业务变更、回执、审计和 Outbox 置于同一原物理事务，重放也重新授权。
 * <p>
 * 验证只登记和推进状态：受理时固定草稿、凭据及模板依据，原子领取后返回短暂敏感输入，调用方必须在事务提交后执行网络探测。
 * 查询和完成均重查依赖，超时未完成或依赖变化则失效；本类不持锁等待网络，不发布模型或修改预算及执行归属。
 * 查询方法也可能使用可写事务取得当前事实或更新失效状态，不能仅凭方法名将其视为无锁只读操作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class DefaultModelDraftService implements ModelDraftService {

    private final ModelResourceStore models;
    private final ModelDraftStore store;
    private final ModelConnectionTemplates templates;
    private final ModelCredentialCipher cipher;
    private final PublicTransactionRunner tx;
    private final WorkspaceGovernanceLocks locks;
    private final CurrentPrincipalResolver principals;
    private final AuthorizationService authorization;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final PlatformIdGenerator ids;
    private final Clock clock;

    public DefaultModelDraftService(ModelResourceStore models, ModelDraftStore store, ModelConnectionTemplates templates,
                                    ModelCredentialCipher cipher, PublicTransactionRunner tx, IdentityProvider identity, TenantStore tenants,
                                    WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                    PreferenceStore preferences, AuthorizationService authorization, CommandReceiptStore receipts,
                                    PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock) {
        this.models = models;
        this.store = store;
        this.templates = templates;
        this.cipher = cipher;
        this.tx = tx;
        this.authorization = authorization;
        this.ids = ids;
        this.clock = clock;
        locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        principals = new CurrentPrincipalResolver(identity);
        commands = new DefaultCommandExecutor(tx, principals, receipts, ids, clock);
        effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
    }

    private void lock(TrustedActor actor, Scope scope) {
        locks.scopes(actor.principal().subjectId(), List.of(scope));
        principals.requireUserCurrent(actor);
    }

    private <T> T read(TrustedActor actor, Scope scope, Supplier<T> work) {
        return tx.required(() -> {
            lock(actor, scope);
            return work.get();
        });
    }

    private void require(TrustedActor actor, Scope scope, ResourceRef ref, Action action) {
        current(scope, ref);
        authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, ref, action, "model-draft"));
    }

    private void workspace(TrustedActor actor, Scope scope) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "model-draft"));
    }

    private ModelResource current(Scope scope, ResourceRef ref) {
        var value = models.findCurrent(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!ref.type().equals(MODEL) || !ref.tenantId().equals(scope.tenantId())
                || !scope.equals(value.managementScope()))
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        return value;
    }

    private CommandScope range(Scope s) {
        return new CommandScope(s.tenantId(), s.workspaceId());
    }

    private void event(TrustedActor actor, Scope scope, ResourceRef ref, String operation, String type, long version) {
        effects.append(actor, range(scope), type, operation, version, Map.of("resourceId", ref.resourceId()));
    }

    @Override
    public ModelList list(TrustedActor actor, Scope scope, String cursor, int size) {
        if (size < 1 || size > 100) throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        return read(actor, scope, () -> {
            workspace(actor, scope);
            String after = cursorKey(actor, scope, cursor);
            var candidates = models.candidates(scope, after, 100);
            List<ModelResource> result = new ArrayList<>();
            String last = after;
            int scanned = 0;
            for (var model : candidates) {
                last = resourceKey(model.resource());
                scanned++;
                try {
                    // 即使调用方外层事务已有旧快照，也按锁定后的当前授权事实过滤。
                    authorization.requireInCurrentTransaction(actor, new AccessRequest(scope, model.resource(), MODEL_READ, "model-list"));
                    result.add(model);
                } catch (PlatformException denied) {
                    if (!Set.of(PlatformErrorCodeEnum.ACCESS_DENIED, PlatformErrorCodeEnum.POLICY_DENIED, PlatformErrorCodeEnum.NOT_FOUND).contains(denied.getCode()))
                        throw denied;
                }
                if (result.size() == size) break;
            }
            String next = scanned < candidates.size() || candidates.size() == 100 ? cursor(actor, scope, last) : null;
            return new ModelList(result, next);
        });
    }

    private String cursor(TrustedActor actor, Scope scope, String key) {
        return key + "." + cipher.fingerprint(DefaultCommandExecutor.digest(List.of("model-list", actor.principal().subjectId(), scope.tenantId(), scope.workspaceId(), key)));
    }

    private String cursorKey(TrustedActor actor, Scope scope, String value) {
        if (value == null) return "";
        if (!value.matches("[a-f0-9]{64}\\.[a-f0-9]{64}") || !java.security.MessageDigest.isEqual(
                cursor(actor, scope, value.substring(0, 64)).getBytes(java.nio.charset.StandardCharsets.UTF_8), value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        return value.substring(0, 64);
    }

    public static String resourceKey(ResourceRef r) {
        return DefaultCommandExecutor.digest(List.of(r.tenantId(), r.type().value(), r.resourceId()));
    }

    @Override
    public List<TemplateView> templates(TrustedActor actor, Scope scope) {
        return read(actor, scope, () -> {
            workspace(actor, scope);
            return templates.available(scope).stream()
                    .map(c -> new TemplateView(c.definition(), c.providerId(), c.protocol())).toList();
        });
    }

    private CredentialView view(TrustedActor actor, ResourceRef ref, Credential value) {
        return new CredentialView(ref.resourceId(), value == null ? "MISSING" : value.revoked() ? "REVOKED" : "CONFIGURED",
                value == null ? 0 : value.version(), value != null && value.responsibleSubjectId().equals(actor.principal().subjectId()), value == null ? "PERSONAL" : value.kind().name());
    }

    @Override
    public DraftView get(TrustedActor actor, Scope scope, ResourceRef ref) {
        return read(actor, scope, () -> {
            require(actor, scope, ref, MODEL_READ);
            return new DraftView(store.draft(ref).orElse(null), view(actor, ref, store.credential(ref).orElse(null)));
        });
    }

    @Override
    public CommandReceipt<Draft> save(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected, String name, Settings settings) {
        // 重放也先检查输入值对象，不能把无效配置写入草稿。
        var input = new Draft(ref, scope, name, settings, expected, PlatformTimes.businessNow(clock));
        return commands.execute(actor, meta, range(scope), "model:draft:save", "model:draft:v1", Draft.class,
                List.of(ref.resourceId(), Long.toString(expected), input.name(), settings.connectionTemplate().id(), settings.connectionTemplate().version(),
                        settings.remoteModel(), Long.toString(settings.contextWindowTokens()), Integer.toString(settings.maxOutputTokens())),
                () -> lock(actor, scope), value -> {
                    if (!value.resource().equals(ref) || !value.scope().equals(scope))
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    require(actor, scope, ref, MODEL_EDIT);
                }, operation -> {
                    require(actor, scope, ref, MODEL_EDIT);
                    templates.require(scope, settings.connectionTemplate());
                    long old = store.draft(ref).map(Draft::version).orElse(0L);
                    check(old == expected);
                    var draft = new Draft(ref, scope, input.name(), settings, Math.incrementExact(expected), PlatformTimes.businessNow(clock));
                    check(store.saveDraft(draft, expected));
                    event(actor, scope, ref, operation, "model:draft:saved", draft.version());
                    return new DefaultCommandExecutor.Result<>(draft, draft.version());
                });
    }

    private void owner(TrustedActor actor, Scope scope, Credential c) {
        if (!c.scope().equals(scope) || !c.responsibleSubjectId().equals(actor.principal().subjectId()))
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
    }

    public static String associatedData(ResourceRef ref, Scope scope, String responsible, long version) {
        return DefaultCommandExecutor.digest(List.of("model-credential-v1", ref.tenantId(), scope.workspaceId(), ref.resourceId(), responsible, Long.toString(version)));
    }

    public static String associatedData(Credential credential) {
        String base = associatedData(credential.resource(), credential.scope(), credential.responsibleSubjectId(), credential.version());
        return credential.kind() == CredentialKind.PERSONAL ? base : DefaultCommandExecutor.digest(List.of("tenant-credential-v1", base));
    }

    @Override
    public CommandReceipt<CredentialView> replaceCredential(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected, String token) {
        return replaceCredential(actor, meta, scope, ref, expected, token, CredentialKind.PERSONAL);
    }

    @Override
    public CommandReceipt<CredentialView> replaceCredential(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected, String token, CredentialKind kind) {
        new BearerCredential(token); // 仅接受合法单行密钥；校验异常不包含密钥内容。
        return commands.execute(actor, meta, range(scope), "model:credential:replace", "model:credential:view:v1", CredentialView.class,
                kind == CredentialKind.PERSONAL ? List.of(ref.resourceId(), Long.toString(expected), cipher.fingerprint(token))
                        : List.of(ref.resourceId(), Long.toString(expected), cipher.fingerprint(token), kind.name()), () -> lock(actor, scope), value -> {
                    require(actor, scope, ref, MODEL_EDIT);
                    owner(actor, scope, store.credential(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)));
                }, operation -> {
                    require(actor, scope, ref, MODEL_EDIT);
                    ModelResource model = current(scope, ref);
                    if ((model.ownership().workspaceId() == null) != (kind == CredentialKind.TENANT)) {
                        throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
                    }
                    var draft = store.draft(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    templates.require(scope, draft.settings().connectionTemplate());
                    var old = store.credential(ref).orElse(null);
                    if (old != null) owner(actor, scope, old);
                    check((old == null ? 0 : old.version()) == expected);
                    String responsible = actor.principal().subjectId();
                    long next = Math.incrementExact(expected);
                    String aad = associatedData(ref, scope, responsible, next);
                    String encrypted = cipher.encrypt(token, kind == CredentialKind.PERSONAL ? aad : DefaultCommandExecutor.digest(List.of("tenant-credential-v1", aad)));
                    var value = new Credential(ref, scope, old == null ? new DefinitionRef("secret", "personal-model-" + ids.nextId(), "v1") : old.secret(),
                            responsible, cipher.activeKeyId(), encrypted, false, next, PlatformTimes.businessNow(clock), kind);
                    check(store.saveCredential(value, expected));
                    event(actor, scope, ref, operation, "model:credential:replaced", next);
                    return new DefaultCommandExecutor.Result<>(view(actor, ref, value), next);
                });
    }

    @Override
    public CommandReceipt<CredentialView> revokeCredential(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long expected) {
        // 原责任主体退出空间后仍可撤销，但此路径不授予资源或配置读取资格。
        return commands.execute(actor, meta, range(scope), "model:credential:revoke", "model:credential:view:v1", CredentialView.class,
                List.of(ref.resourceId(), Long.toString(expected)), () -> lock(actor, scope), value ->
                        owner(actor, scope, store.credential(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND))), operation -> {
                    current(scope, ref);
                    var old = store.credential(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    owner(actor, scope, old);
                    check(old.version() == expected);
                    var value = old.revoked() ? old : new Credential(ref, scope, old.secret(), old.responsibleSubjectId(), old.keyId(), null, true,
                            Math.incrementExact(expected), PlatformTimes.businessNow(clock), old.kind());
                    if (!old.revoked()) check(store.saveCredential(value, expected));
                    event(actor, scope, ref, operation, "model:credential:revoked", value.version());
                    return new DefaultCommandExecutor.Result<>(view(actor, ref, value), value.version());
                });
    }

    @Override
    public CommandReceipt<Validation> requestValidation(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef ref, long dv, long cv) {
        return commands.execute(actor, meta, range(scope), "model:draft:validate", "model:validation:v1", Validation.class,
                List.of(ref.resourceId(), Long.toString(dv), Long.toString(cv)), () -> lock(actor, scope), value -> validationCurrent(actor, scope, value.id()), operation -> {
                    require(actor, scope, ref, MODEL_VALIDATE);
                    var draft = store.draft(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                    var credential = usableCredential(actor, scope, ref);
                    check(draft.version() == dv && credential.version() == cv);
                    var connection = templates.require(scope, draft.settings().connectionTemplate());
                    var value = new Validation(ids.nextId(), ref, scope, actor.principal().subjectId(), dv, cv, templates.digest(connection),
                            ValidationState.PENDING, "PENDING", PlatformTimes.businessNow(clock).plusSeconds(30), null, 0);
                    check(store.insertValidation(value));
                    event(actor, scope, ref, operation, "model:validation:requested", 0);
                    return new DefaultCommandExecutor.Result<>(value, 0);
                });
    }

    private Credential usableCredential(TrustedActor actor, Scope scope, ResourceRef ref) {
        var credential = store.credential(ref).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        owner(actor, scope, credential);
        if (credential.revoked()) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        return credential;
    }

    private Validation validationCurrent(TrustedActor actor, Scope scope, String id) {
        var value = store.validation(scope, id).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!value.scope().equals(scope) || !value.requestedBy().equals(actor.principal().subjectId()))
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        require(actor, scope, value.resource(), MODEL_VALIDATE);
        return value;
    }

    private boolean unchanged(Validation v) {
        var d = store.draft(v.resource()).orElse(null);
        var c = store.credential(v.resource()).orElse(null);
        if (d == null || c == null || c.revoked() || d.version() != v.draftVersion() || c.version() != v.credentialVersion())
            return false;
        return templates.digest(templates.require(v.scope(), d.settings().connectionTemplate())).equals(v.templateDigest());
    }

    private Validation change(Validation v, ValidationState state, String code) {
        var next = new Validation(v.id(), v.resource(), v.scope(), v.requestedBy(), v.draftVersion(), v.credentialVersion(), v.templateDigest(),
                state, code, v.expiresAt(), PlatformTimes.businessNow(clock), Math.incrementExact(v.version()));
        check(store.replaceValidation(next, v.version()));
        return next;
    }

    private boolean expired(Validation v) {
        return !clock.instant().isBefore(v.expiresAt());
    }

    @Override
    public Optional<Probe> claimValidation(TrustedActor actor, Scope scope, String id) {
        return read(actor, scope, () -> {
            var v = validationCurrent(actor, scope, id);
            if (v.state() != ValidationState.PENDING) return Optional.empty();
            if (expired(v) || !unchanged(v)) {
                change(v, ValidationState.STALE, "STALE");
                return Optional.empty();
            }
            var c = usableCredential(actor, scope, v.resource());
            var d = store.draft(v.resource()).orElseThrow();
            var connection = templates.require(scope, d.settings().connectionTemplate());
            String plaintext = cipher.decrypt(c, associatedData(c));
            change(v, ValidationState.RUNNING, "RUNNING");
            return Optional.of(new Probe(connection, d.settings().remoteModel(), plaintext));
        });
    }

    @Override
    public Validation completeValidation(TrustedActor actor, Scope scope, String id, ProbeResult result) {
        return read(actor, scope, () -> {
            var v = validationCurrent(actor, scope, id);
            if (v.state() != ValidationState.RUNNING) return effective(v);
            var value = expired(v) || !unchanged(v) ? change(v, ValidationState.STALE, "STALE")
                    : change(v, result.valid() ? ValidationState.SUCCEEDED : ValidationState.FAILED, result.code());
            event(actor, scope, v.resource(), v.id(), "model:validation:completed", value.version());
            return value;
        });
    }

    private Validation effective(Validation v) {
        if (v.state() == ValidationState.STALE) return v;
        if ((v.state() == ValidationState.PENDING || v.state() == ValidationState.RUNNING) && expired(v) || !unchanged(v))
            return change(v, ValidationState.STALE, "STALE");
        return v;
    }

    @Override
    public Validation validation(TrustedActor actor, Scope scope, String id) {
        return read(actor, scope, () -> effective(validationCurrent(actor, scope, id)));
    }

    private static void check(boolean value) {
        if (!value) throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-preparation");
    }
}

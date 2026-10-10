package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspacePreferenceService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspacePreference;
import com.arte.core.pojo.workspace.WorkspaceViews;
import com.arte.core.spi.workspace.PreferenceStore;

import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;

/**
 * 本人导航偏好；失效引用不授予资格，默认关联不能被普通更新替换。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspacePreferenceService implements WorkspacePreferenceService {

    private final CurrentPrincipalResolver principals;
    private final PreferenceStore preferences;
    private final DefaultAuthorizationService authorization;
    private final DefaultCommandExecutor commands;
    private final WorkspaceGovernanceLocks locks;
    private final WorkspaceMutationEffects effects;

    public DefaultWorkspacePreferenceService(CurrentPrincipalResolver principals, PreferenceStore preferences,
                                             DefaultAuthorizationService authorization, DefaultCommandExecutor commands, WorkspaceGovernanceLocks locks,
                                             WorkspaceMutationEffects effects) {
        this.principals = Objects.requireNonNull(principals);
        this.preferences = Objects.requireNonNull(preferences);
        this.authorization = Objects.requireNonNull(authorization);
        this.commands = Objects.requireNonNull(commands);
        this.locks = Objects.requireNonNull(locks);
        this.effects = Objects.requireNonNull(effects);
    }

    @Override
    public WorkspaceViews.PreferenceView get(TrustedActor actor) {
        return get(actor, false);
    }

    /**
     * 写命令内部生成／校验结果时，偏好引用也按当前资格过滤。
     */
    public WorkspaceViews.PreferenceView getCurrent(TrustedActor actor) {
        return get(actor, true);
    }

    private WorkspaceViews.PreferenceView get(TrustedActor actor, boolean currentRead) {
        if (currentRead) locks.subject(actor.principal().subjectId());
        var preference = own(actor, currentRead);
        if (currentRead) {
            Set<Scope> references = new HashSet<>(preference.favorites());
            references.add(preference.defaultScope());
            if (preference.startupScope() != null) references.add(preference.startupScope());
            if (preference.lastScope() != null) references.add(preference.lastScope());
            locks.parents(actor.principal().subjectId(), references.stream().map(Scope::tenantId).toList());
        }
        requireRead(actor, preference.defaultScope(), currentRead);
        Scope startup = preference.startupScope();
        if (startup != null && !canRead(actor, startup, currentRead)) startup = preference.defaultScope();
        Scope last = preference.lastScope();
        if (last != null && !canRead(actor, last, currentRead)) last = null;
        Set<Scope> favorites = new HashSet<>();
        for (Scope scope : preference.favorites()) if (canRead(actor, scope, currentRead)) favorites.add(scope);
        return new WorkspaceViews.PreferenceView(preference.defaultScope(), startup, last, favorites, preference.version());
    }

    @Override
    public CommandReceipt<WorkspaceViews.PreferenceView> update(TrustedActor actor, WorkspaceCommands.UpdatePreference command) {
        String subjectId = principals.requireUser(actor).subjectId();
        if (command.favorites().size() > 100)
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, command.meta().traceId());
        return commands.execute(actor, command.meta(), new CommandScope(null, null), "preference:update", "preference:view:v1",
                WorkspaceViews.PreferenceView.class, fields(command), () -> {
                    locks.subject(subjectId);
                    WorkspacePreference current = own(actor, true);
                    Set<Scope> targets = targets(command);
                    targets.add(current.defaultScope());
                    locks.scopes(subjectId, targets);
                }, value -> {
                    WorkspacePreference current = own(actor, true);
                    if (!value.defaultScope().equals(current.defaultScope())) throw unavailable();
                    requireRead(actor, value.defaultScope(), true);
                    if (value.startupScope() != null) requireRead(actor, value.startupScope(), true);
                    if (value.lastScope() != null) requireRead(actor, value.lastScope(), true);
                    for (Scope scope : value.favorites()) requireRead(actor, scope, true);
                }, operationId -> {
                    WorkspacePreference current = own(actor, true);
                    if (current.version() != command.expectedPreferenceVersion()) {
                        throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, command.meta().traceId());
                    }
                    requireRead(actor, current.defaultScope(), true);
                    for (Scope scope : targets(command)) requireRead(actor, scope, true);
                    var updated = new WorkspacePreference(subjectId, current.defaultScope(), command.startupScope(),
                            command.lastScope(), command.favorites(), Math.incrementExact(current.version()));
                    if (!preferences.replace(updated, current.version())) {
                        throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, command.meta().traceId());
                    }
                    effects.append(actor, new CommandScope(null, null), "preference:updated", operationId, updated.version(), Map.of());
                    return new DefaultCommandExecutor.Result<>(WorkspaceViewMapper.preference(updated), updated.version());
                });
    }

    private WorkspacePreference own(TrustedActor actor, boolean currentRead) {
        String subjectId = (currentRead ? principals.requireUserCurrent(actor) : principals.requireUser(actor)).subjectId();
        var preference = (currentRead ? preferences.findCurrent(subjectId) : preferences.find(subjectId))
                .orElseThrow(() -> new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "preference-read"));
        if (!preference.subjectId().equals(subjectId)) throw unavailable();
        return preference;
    }

    private void requireRead(TrustedActor actor, Scope scope, boolean currentRead) {
        var request = DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "preference-target");
        if (currentRead) authorization.requireInCurrentTransaction(actor, request);
        else authorization.require(actor, request);
    }

    private boolean canRead(TrustedActor actor, Scope scope, boolean currentRead) {
        var request = DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "preference-target");
        var decision = currentRead ? authorization.checkCurrent(actor, request) : authorization.check(actor, request);
        if (decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) throw unavailable();
        if (decision.reason() == AccessReasonEnum.SUBJECT_INACTIVE) {
            if (currentRead) principals.requireUserCurrent(actor);
            else principals.requireUser(actor);
        }
        return decision.allowed();
    }

    private static Set<Scope> targets(WorkspaceCommands.UpdatePreference command) {
        Set<Scope> scopes = new HashSet<>(command.favorites());
        if (command.startupScope() != null) scopes.add(command.startupScope());
        if (command.lastScope() != null) scopes.add(command.lastScope());
        return scopes;
    }

    private static List<String> fields(WorkspaceCommands.UpdatePreference command) {
        List<String> fields = new ArrayList<>();
        fields.add(Long.toString(command.expectedPreferenceVersion()));
        addScope(fields, command.startupScope());
        addScope(fields, command.lastScope());
        fields.add(Integer.toString(command.favorites().size()));
        command.favorites().stream().sorted(Comparator.comparing(Scope::tenantId).thenComparing(Scope::workspaceId))
                .forEach(scope -> addScope(fields, scope));
        return fields;
    }

    private static void addScope(List<String> fields, Scope scope) {
        fields.add(scope == null ? null : scope.tenantId());
        fields.add(scope == null ? null : scope.workspaceId());
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "preference-read");
    }
}

package com.arte.ainew.application.tool.management;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.release.StaticToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.auth.ToolManagementAccess.Operation;
import com.arte.ainew.spi.auth.ToolManagementAccess.Permit;
import com.arte.ainew.spi.auth.ToolManagementAccess.Session;
import com.arte.ainew.spi.persistence.ToolBindingStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolDiscoveryManagementStore;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 对外 TOOL 管理用例：所有入口每次重查当前身份／Scope／权限，写入把最终门控传入原事务。
 * 只开放现有本地目录、发布和个人绑定；默认绑定只在缺失时创建，不触发工具执行或预算初始化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolManagementService {

    public record ToolView(ToolDefinitionRevision definition, ToolRelease release) {
    }

    public record BindingView(ToolBindingDefinition definition, ToolBindingRevision revision) {
    }

    public record ToolDetail(ToolView tool, Map<DefinitionRef, ToolSchemaDefinition> schemas) {
    }

    public record Refresh(StoreOutcome<ToolDiscoveryReceipt> receipt, StaticToolDiscovery.State node) {
    }

    /**
     * 过滤后的有界扫描页；空 records 不代表结束，必须查看 nextCursor。
     */
    public record Page<T>(List<T> records, String nextCursor, int scanned) {
        public Page {
            records = List.copyOf(records);
        }
    }

    private final ToolCatalogStore catalog;
    private final ToolReleaseStore releases;
    private final ToolBindingStore bindings;
    private final ToolReleaseService releaseService;
    private final ToolBindingService bindingService;
    private final StaticToolPublicationValidator validator;
    private final StaticToolDiscovery discovery;
    private final ToolDiscoveryManagementStore discoveryStore;

    public ToolManagementService(ToolCatalogStore catalog, ToolReleaseStore releases, ToolBindingStore bindings,
                                 ToolReleaseService releaseService, ToolBindingService bindingService, StaticToolPublicationValidator validator,
                                 StaticToolDiscovery discovery, ToolDiscoveryManagementStore discoveryStore) {
        this.catalog = catalog;
        this.releases = releases;
        this.bindings = bindings;
        this.releaseService = releaseService;
        this.bindingService = bindingService;
        this.validator = validator;
        this.discovery = discovery;
        this.discoveryStore = discoveryStore;
    }

    /**
     * 完整本地视图统一刷新；重放不重新读取来源或伪造本节点健康。
     */
    public Mono<Refresh> refresh(Session session, Scope scope, String key, String reason) {
        ContractChecks.id(key, "idempotencyKey");
        ContractChecks.text(reason, "reason", 1024);
        return session.authorize(scope, Operation.MANAGE).flatMap(permit -> {
            Runnable check = () -> permit.requireInCurrentTransaction(Set.of());
            return discoveryStore.find(permit.actor(), scope, key, reason, check).map(receipt -> new Refresh(receipt, discovery.state()))
                    .switchIfEmpty(Mono.defer(() -> {
                        var committed = new java.util.concurrent.atomic.AtomicReference<ToolDiscoveryManagementStore.Commit>();
                        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                        return discovery.refresh(batch -> discoveryStore.commit(permit.actor(), scope, key, reason, batch, check)
                                        .doOnNext(committed::set).doOnError(failure::set).map(ToolDiscoveryManagementStore.Commit::catalog))
                                .flatMap(result -> {
                                    if (failure.get() != null) return Mono.error(failure.get());
                                    if (committed.get() != null)
                                        return Mono.just(new Refresh(committed.get().receipt(), result.state()));
                                    if (result.code() == StaticToolDiscovery.Code.BUSY)
                                        return Mono.error(new com.arte.ainew.application.support.AdmissionException(com.arte.core.enums.ResultCodeEnum.AI_INVALID_STATE));
                                    if (result.code() == StaticToolDiscovery.Code.STORE_FAILED)
                                        return Mono.error(new IllegalStateException("Local catalog refresh could not commit"));
                                    return discoveryStore.failure(permit.actor(), scope, key, reason, ToolDiscoveryReceipt.Code.valueOf(result.code().name()), check)
                                            .map(receipt -> new Refresh(receipt, result.state()));
                                });
                    }));
        });
    }

    public Mono<Page<ToolView>> tools(Session session, Scope scope, boolean managed, DefinitionRef provider, String cursor, int limit) {
        return tools(session, scope, managed, provider, cursor, limit, null, null, null, null);
    }

    /**
     * 筛选条件绑定游标；在有界候选页上筛选，空可见页仍可继续，不伪造 total。
     */
    public Mono<Page<ToolView>> tools(Session session, Scope scope, boolean managed, DefinitionRef provider, String cursor, int limit,
                                      String query, String tag, String providerId, ToolRelease.State state) {
        pageSize(limit);
        if (provider != null) provider.requireType("tool-provider");
        return session.authorize(scope, managed ? Operation.MANAGE : Operation.LIST).flatMap(permit -> {
            String context = context(permit, "tools", String.valueOf(provider), Boolean.toString(managed),
                    Objects.toString(query, ""), Objects.toString(tag, ""), Objects.toString(providerId, ""), Objects.toString(state, ""));
            var candidates = provider == null ? catalog.listTools(after(cursor, context), limit) : catalog.listTools(provider, after(cursor, context), limit);
            return candidates.flatMap(page -> releases.findKnown(page.items().stream().map(revision -> revision.tool().definition()).toList())
                    .map(states -> new Page<>(page.items().stream()
                            .filter(revision -> managed || permit.executionScopes().containsAll(revision.tool().risk().requiredScopes()))
                            .map(revision -> new ToolView(revision, Objects.requireNonNull(states.get(revision.tool().definition()))))
                            .filter(view -> managed || view.release().publication() != null)
                            .filter(view -> matches(view, query, tag, providerId, state)).toList(), next(page.nextCursor(), context), page.items().size())));
        });
    }

    private boolean matches(ToolView view, String query, String tag, String providerId, ToolRelease.State state) {
        var tool = view.definition().tool();
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return (search.isEmpty() || (tool.title() + " " + tool.definition().id() + " " + tool.description()).toLowerCase(Locale.ROOT).contains(search))
                && (tag == null || tag.isBlank() || tool.tags().contains(tag))
                && (providerId == null || providerId.isBlank() || view.definition().provider().id().equals(providerId))
                && (state == null || view.release().state() == state);
    }

    public Mono<ToolDetail> tool(Session session, Scope scope, boolean managed, DefinitionRef ref) {
        Objects.requireNonNull(ref).requireType("tool");
        return session.authorize(scope, managed ? Operation.MANAGE : Operation.LIST).flatMap(permit ->
                catalog.findTool(ref).flatMap(revision -> visible(revision, permit, managed)).switchIfEmpty(notFound())
                        .flatMap(view -> Flux.fromIterable(new LinkedHashSet<>(List.of(view.definition().tool().inputSchema(), view.definition().tool().outputSchema(),
                                        view.definition().tool().configurationSchema())))
                                .concatMap(schema -> catalog.findSchema(schema).switchIfEmpty(Mono.error(new IllegalStateException("Missing tool Schema"))))
                                .collectMap(ToolSchemaDefinition::definition).map(schemas -> new ToolDetail(view, Map.copyOf(schemas)))));
    }

    private Mono<ToolView> visible(ToolDefinitionRevision revision, Permit permit, boolean managed) {
        if (!managed && !permit.executionScopes().containsAll(revision.tool().risk().requiredScopes()))
            return Mono.empty();
        return releases.find(revision.tool().definition()).filter(release -> managed || release.publication() != null).map(release -> new ToolView(revision, release));
    }

    public Mono<Page<ToolProviderDefinition>> providers(Session session, Scope scope, String cursor, int limit) {
        pageSize(limit);
        return session.authorize(scope, Operation.MANAGE).flatMap(permit -> {
            String context = context(permit, "providers");
            return catalog.listProviders(after(cursor, context), limit).map(page -> new Page<>(page.items(), next(page.nextCursor(), context), page.items().size()));
        });
    }

    public Mono<ToolPublicationValidation> validate(Session session, Scope scope, DefinitionRef tool, ToolReleaseCommand.Review review) {
        Objects.requireNonNull(tool).requireType("tool");
        return session.authorize(scope, Operation.MANAGE).flatMap(permit -> catalog.findTool(tool).switchIfEmpty(notFound())
                .flatMap(ignored -> validator.validate(tool, review)));
    }

    public Mono<StoreOutcome<ToolReleaseReceipt>> release(Session session, Scope scope, ToolReleaseCommand command) {
        return session.authorize(scope, Operation.MANAGE).flatMap(permit -> releaseService.execute(permit.actor(), command,
                () -> permit.requireInCurrentTransaction(Set.of())));
    }

    public Mono<Page<ToolReleaseReceipt>> releaseHistory(Session session, Scope scope, DefinitionRef tool, String cursor, int limit) {
        pageSize(limit);
        return session.authorize(scope, Operation.MANAGE).flatMap(permit -> {
            String context = context(permit, "release-history", tool.toString());
            return releases.history(tool, after(cursor, context), limit).map(page -> new Page<>(page.items(), next(page.nextCursor(), context), page.items().size()));
        });
    }

    public Mono<Page<ToolBindingDefinition>> bindings(Session session, Scope scope, String cursor, int limit) {
        pageSize(limit);
        return session.authorize(scope, Operation.LIST).flatMap(permit -> {
            String context = context(permit, "bindings");
            return bindings.list(permit.owner(), after(cursor, context), limit).map(page -> new Page<>(page.items(), next(page.nextCursor(), context), page.items().size()));
        });
    }

    public Mono<BindingView> binding(Session session, Scope scope, String id) {
        return session.authorize(scope, Operation.LIST).flatMap(permit -> bindings.find(permit.owner(), id).switchIfEmpty(notFound())
                .flatMap(header -> bindings.revision(permit.owner(), header.revision()).switchIfEmpty(Mono.error(new IllegalStateException("Missing binding revision")))
                        .map(revision -> new BindingView(header, revision))));
    }

    public Mono<ToolBindingRevision> revision(Session session, Scope scope, DefinitionRef ref) {
        return session.authorize(scope, Operation.LIST).flatMap(permit -> bindings.revision(permit.owner(), ref).switchIfEmpty(notFound()));
    }

    /**
     * 显式准备本人默认绑定；固定版本和配置进入原始命令，存储按 owner＋工具身份原子去重。
     * 已有默认绑定保持用户选择，不自动升级或恢复启用；每次及事务内仍复核当前 WRITE 资格。
     */
    public Mono<StoreOutcome<ToolBindingReceipt>> prepareDefault(Session session, Scope scope, DefinitionRef tool,
                                                                 StructuredValue.ObjectValue configuration, String key) {
        Objects.requireNonNull(tool).requireType("tool");
        return tool(session, scope, false, tool).flatMap(detail -> {
            var title = detail.tool().definition().tool().title();
            var settings = new ToolBindingSettings(tool, ToolBindingSettings.VersionPolicy.PINNED,
                    title.length() > 200 ? title.substring(0, 200) : title, true, configuration, Map.of(), null, null);
            var command = new ToolBindingCommand(ToolBindingCommand.Action.ENSURE_DEFAULT, null, 0, key,
                    "准备本人默认工具配置", settings, null, null, false);
            return changeBinding(session, scope, command);
        });
    }

    public Mono<StoreOutcome<ToolBindingReceipt>> changeBinding(Session session, Scope scope, ToolBindingCommand command) {
        boolean disabling = command.action() == ToolBindingCommand.Action.SET_ENABLED && !command.enabled();
        return session.authorize(scope, disabling ? Operation.BINDING_DISABLE : Operation.BINDING_WRITE).flatMap(permit -> {
            // 撤销不依赖原工具、资源 scopes 或节点健康；其他变更按服务端固定契约校验当前权限上限。
            Mono<Set<String>> required;
            if (disabling) required = Mono.just(Set.of());
            else if (command.settings() != null) required = required(command.settings().tool());
            else
                required = bindings.find(permit.owner(), command.bindingId()).flatMap(header -> bindings.revision(permit.owner(), header.revision()))
                        .flatMap(revision -> required(revision.settings().tool())).defaultIfEmpty(Set.of());
            return required.flatMap(scopes -> {
                if (!permit.executionScopes().containsAll(scopes))
                    return Mono.error(new AccessDeniedException("Tool scope denied"));
                return bindings.executeGuarded(permit.actor(), permit.owner(), command, () -> permit.requireInCurrentTransaction(scopes));
            });
        });
    }

    public Mono<ToolBindingUpgradePreview> preview(Session session, Scope scope, String id, long version, ToolBindingSettings settings) {
        return session.authorize(scope, Operation.BINDING_WRITE).flatMap(permit -> required(settings.tool()).flatMap(scopes -> {
            if (!permit.executionScopes().containsAll(scopes))
                return Mono.error(new AccessDeniedException("Tool scope denied"));
            return bindingService.preview(permit.owner(), id, version, settings).switchIfEmpty(notFound());
        }));
    }

    public Mono<Page<ToolBindingReceipt>> bindingHistory(Session session, Scope scope, String id, String cursor, int limit) {
        pageSize(limit);
        return session.authorize(scope, Operation.LIST).flatMap(permit -> {
            String context = context(permit, "binding-history", id);
            return bindings.find(permit.owner(), id).switchIfEmpty(notFound()).flatMap(ignored ->
                    bindings.history(permit.owner(), id, after(cursor, context), limit)
                            .map(page -> new Page<>(page.items(), next(page.nextCursor(), context), page.items().size())));
        });
    }

    private Mono<Set<String>> required(DefinitionRef tool) {
        return catalog.findTool(tool).map(revision -> revision.tool().risk().requiredScopes()).defaultIfEmpty(Set.of());
    }

    private static String context(Permit permit, String... parts) {
        var fields = new ArrayList<>(List.of(permit.owner().tenantId(), permit.owner().workspaceId(), permit.owner().subjectId(), permit.operation().name()));
        fields.addAll(List.of(parts));
        return com.arte.ainew.serialization.CanonicalJson.key(fields.toArray(String[]::new));
    }

    private static String after(String cursor, String context) {
        if (cursor == null) return null;
        ContractChecks.require(cursor.matches("t1-[0-9a-f]{64}-[0-9a-f]{64}") && cursor.substring(3, 67).equals(context), "Tool cursor does not match query scope");
        return cursor.substring(68);
    }

    private static String next(String key, String context) {
        return key == null ? null : "t1-" + context + "-" + key;
    }

    private static void pageSize(int limit) {
        ContractChecks.range(limit, "size", 1, 100);
    }

    private static <T> Mono<T> notFound() {
        return Mono.error(new com.arte.ainew.application.support.AdmissionException(com.arte.core.enums.ResultCodeEnum.AI_NOT_FOUND));
    }
}

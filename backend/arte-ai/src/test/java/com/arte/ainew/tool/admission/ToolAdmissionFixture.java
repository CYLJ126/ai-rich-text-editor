package com.arte.ainew.tool.admission;

import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.*;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.TrustedActor;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 隔离 H2 与真实目录／治理／受理事务；权限表只模拟宿主当前资格，不代表平台 RBAC 已接线。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
final class ToolAdmissionFixture implements AutoCloseable {

    final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");
    final Clock clock = Clock.systemUTC();
    final TrustedActor actor = new TrustedActor(new Principal("subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), clock.instant());
    final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    final Scheduler io = Schedulers.newBoundedElastic(8, 128, "tool-admission-db-test");
    final Scheduler metadata = Schedulers.newBoundedElastic(2, 32, "tool-admission-metadata-test");
    final JdbcDataSource source = new JdbcDataSource();
    final Connection ddl;
    final JdbcTemplate jdbc;
    final MybatisToolCatalogStore catalog;
    final MybatisToolReleaseStore releases;
    final MybatisToolBindingStore bindings;
    final MybatisExecutionPersistence executions;
    final MybatisToolAdmissionStore store;
    final LocalToolDiscovery discovery;
    final LocalToolRegistry registry;
    final ToolReleaseService releaseService;
    final ToolBindingService bindingService;
    final ToolAdmissionService service;
    final Fixture v1, v2;
    final ToolExecutionPolicy limits = new ToolExecutionPolicy(Duration.ofSeconds(30), 10, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE);
    ToolBindingDefinition binding;
    final AtomicInteger authorizations = new AtomicInteger(), guards = new AtomicInteger(), wakeups = new AtomicInteger();
    Consumer<ExecutionContext> authorizationHook = ignored -> {
    };
    Consumer<ToolExecutionSnapshot> commitHook = ignored -> {
    };
    Function<ExecutionAuthorization, ExecutionAuthorization> permissions = value -> value;

    final ToolAdmissionAccess access = this::authorize;

    private Mono<ToolAdmissionAccess.Permit> authorize(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution plan) {
        return Mono.defer(() -> {
            authorizations.incrementAndGet();
            if (!jdbc.queryForObject("SELECT enabled FROM tool_test_permission WHERE subject_id='subject'", Boolean.class))
                return Mono.error(new AccessDeniedException("Test current qualification denied"));
            authorizationHook.accept(context);
            var current = permissions.apply(context.authorization());
            return Mono.just(new ToolAdmissionAccess.Permit() {
                public ExecutionAuthorization authorization() {
                    return current;
                }

                public void requireInCurrentTransaction(ToolExecutionSnapshot actual) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertTrue(TransactionSynchronizationManager.hasResource(source));
                    guards.incrementAndGet();
                    if (!jdbc.queryForObject("SELECT enabled FROM tool_test_permission WHERE subject_id='subject' FOR UPDATE", Boolean.class))
                        throw new AccessDeniedException("Test permission revoked before commit");
                    if (!actual.binding().owner().equals(ExecutionOwner.from(context))
                            || !current.scopes().containsAll(actual.binding().tool().tool().risk().requiredScopes()))
                        throw new AccessDeniedException("Test actual snapshot qualification denied");
                    commitHook.accept(actual);
                }
            });
        });
    }

    ToolAdmissionFixture() throws Exception {
        this(new ToolExecutionPolicy(Duration.ofSeconds(5), 3, Duration.ZERO, 1024, 4096, ToolExecutionPolicy.ApprovalMode.NONE),
                new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of("resource:read"), Set.of()));
    }

    ToolAdmissionFixture(ToolExecutionPolicy policy, ToolRiskProfile risk) throws Exception {
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ddl = source.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).populate(ddl);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE arte_ai_tool_execution_snapshot");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-tool-admission-migration.sql")).populate(ddl);
        jdbc.execute("CREATE TABLE tool_test_permission(subject_id VARCHAR(32) PRIMARY KEY, enabled BOOLEAN NOT NULL)");
        jdbc.update("INSERT INTO tool_test_permission VALUES('subject',true)");
        catalog = new MybatisToolCatalogStore(source, codec, io, clock);
        releases = new MybatisToolReleaseStore(source, codec, io, clock);
        bindings = new MybatisToolBindingStore(source, codec, io, clock, limits, catalog, releases);
        executions = new MybatisExecutionPersistence(source, codec, io, wakeups::incrementAndGet, wakeups::incrementAndGet);
        store = new MybatisToolAdmissionStore(source, codec, io, executions, bindings, catalog);
        v1 = tool("v1", policy, risk);
        v2 = tool("v2", policy, risk);
        var definitions = new LocalToolDefinitionSource.Definitions(List.of(v1.schema()), List.of(v1.revision(), v2.revision()));
        registry = new LocalToolRegistry(List.of(new LocalToolRegistry.Registration(v1.provider(), () -> Mono.just(definitions),
                Map.of(v1.adapterRef(), adapter(v1.revision().tool().discoveredCapability()), v2.adapterRef(), adapter(v2.revision().tool().discoveredCapability())))));
        discovery = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), metadata, clock, Duration.ofSeconds(2));
        assertTrue(discovery.refresh().block(Duration.ofSeconds(10)).state().discoveryHealthy());
        var validator = new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), metadata, clock, Duration.ofSeconds(2));
        releaseService = new ToolReleaseService(releases, validator);
        bindingService = new ToolBindingService(bindings, discovery, registry, metadata, Duration.ofSeconds(2));
        publish(v1, null);
        binding = createBinding("binding", ToolBindingSettings.VersionPolicy.FOLLOW_COMPATIBLE);
        service = service(store, access, clock);
    }

    private Fixture tool(String version, ToolExecutionPolicy policy, ToolRiskProfile risk) {
        var base = fixture("text", version);
        var old = base.revision().tool();
        var tool = new ToolDefinition(old.definition(), old.providerId(), old.title(), old.description(), old.tags(), old.capability(),
                old.inputSchema(), old.outputSchema(), old.configurationSchema(), old.defaultConfiguration(), old.capabilities(), risk, policy, "0".repeat(64));
        return new Fixture(base.provider(), base.schema(), new ToolDefinitionRevision(withTitle(tool, tool.title()), base.provider().definition(), base.revision().target()), base.adapterRef());
    }

    void publish(Fixture fixture, Fixture predecessor) {
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object(), object())), "review", null,
                fixture.revision().tool().defaultPolicy().approvalMode(), predecessor == null ? null : predecessor.revision().tool().definition(),
                predecessor == null ? null : "compatible");
        var outcome = releaseService.execute(actor, new ToolReleaseCommand(fixture.revision().tool().definition(), ToolReleaseCommand.Action.PUBLISH,
                0, "publish-" + fixture.revision().tool().definition().version(), "review", review, null)).block(Duration.ofSeconds(10));
        assertEquals(ToolReleaseReceipt.Rejection.NONE, outcome.value().rejection());
    }

    ToolBindingDefinition createBinding(String key, ToolBindingSettings.VersionPolicy policy) {
        var settings = new ToolBindingSettings(v1.revision().tool().definition(), policy, key, true, object(), Map.of(), null, null);
        return bindings.execute(actor, owner, new ToolBindingCommand(ToolBindingCommand.Action.SAVE, null, 0, key, "create", settings,
                null, null, false)).block(Duration.ofSeconds(10)).value().result().value();
    }

    ExecutionContext context(String id, String key) {
        return new ExecutionContext(id, "trace-" + id, new ExecutionAuthorization(new ExecutionPrincipal(owner.subjectId(), "alice", ExecutionPrincipal.Kind.USER),
                owner.tenantId(), owner.workspaceId(), Set.of("ai:invoke", "resource:read"), "grant-" + id),
                clock.instant().plusSeconds(30), null, null, "release", key);
    }

    ToolAdmissionInput input() {
        return new ToolAdmissionInput(binding.bindingId(), object(), null, null, null);
    }

    ToolAdmissionService service(ToolAdmissionStore storage, ToolAdmissionAccess authorization, Clock current) {
        return new ToolAdmissionService(bindingService, catalog, storage, authorization, new ToolSchemaValidator(), limits, metadata, current);
    }

    void disable() {
        binding = bindings.execute(actor, owner, new ToolBindingCommand(ToolBindingCommand.Action.SET_ENABLED, binding.bindingId(), binding.version(),
                "disable-" + binding.version(), "stop", null, false, null, false)).block(Duration.ofSeconds(10)).value().result().value();
    }

    void changeConfiguration() {
        var settings = new ToolBindingSettings(v1.revision().tool().definition(), ToolBindingSettings.VersionPolicy.FOLLOW_COMPATIBLE,
                "updated", true, object("text", text("updated-config")), Map.of(), null, null);
        binding = bindings.execute(actor, owner, new ToolBindingCommand(ToolBindingCommand.Action.SAVE, binding.bindingId(), binding.version(),
                "update-" + binding.version(), "change", settings, null, null, false)).block(Duration.ofSeconds(10)).value().result().value();
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Override
    public void close() throws Exception {
        metadata.dispose();
        io.dispose();
        jdbc.execute("DROP ALL OBJECTS");
        ddl.close();
    }
}

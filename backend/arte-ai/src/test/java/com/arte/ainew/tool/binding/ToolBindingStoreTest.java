package com.arte.ainew.tool.binding;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.release.StaticToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolBindingStore;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.persistence.mybatis.MybatisToolReleaseStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.TrustedActor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.pojo.tool.ToolBindingResult.Rejection.*;
import static com.arte.ainew.pojo.tool.ToolBindingSettings.VersionPolicy.FOLLOW_COMPATIBLE;
import static com.arte.ainew.pojo.tool.ToolBindingSettings.VersionPolicy.PINNED;
import static com.arte.ainew.tool.binding.ToolBindingConstraintsTest.policy;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 真实隔离 MyBatis/H2 事务：不连接开发库，不调用工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public class ToolBindingStoreTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Instant START = Instant.parse("2026-10-18T02:00:00.123456Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }
    };
    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "user");
    private final TrustedActor actor = actor("user");
    private final Fixture v1 = fixture("text", "z-old"), v2 = fixture("text", "a-next"), v3 = fixture("text", "m-final");
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final ToolExecutionPolicy limits = policy(60, 10, 0, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE);
    private JdbcDataSource ds;
    private JdbcTemplate jdbc;
    private Connection ddl;
    private Scheduler scheduler;
    private MybatisToolCatalogStore catalog;
    private MybatisToolReleaseStore releases;
    private MybatisToolBindingStore first, second;

    @Before
    public void setup() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        ddl = ds.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).populate(ddl);
        jdbc = new JdbcTemplate(ds);
        for (String table : List.of("command", "resolution", "revision"))
            jdbc.execute("DROP TABLE arte_ai_tool_binding_" + table);
        jdbc.execute("DROP TABLE arte_ai_tool_binding");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-tool-binding-migration.sql")).populate(ddl);
        scheduler = Schedulers.newBoundedElastic(6, 64, "tool-binding-test");
        catalog = new MybatisToolCatalogStore(ds, codec, scheduler, clock);
        releases = new MybatisToolReleaseStore(ds, codec, scheduler, clock);
        first = store(limits);
        second = store(limits);
    }

    @After
    public void cleanup() throws Exception {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
        ddl.close();
    }

    @Test
    public void constructsWithoutSqlAndRequiresOnePhysicalDataSource() {
        var forbidden = new DelegatingDataSource(ds) {
            public Connection getConnection() {
                throw new AssertionError("No startup SQL");
            }

            public Connection getConnection(String a, String b) {
                return getConnection();
            }
        };
        var c = new MybatisToolCatalogStore(forbidden, codec, scheduler, clock);
        var r = new MybatisToolReleaseStore(forbidden, codec, scheduler, clock);
        assertNotNull(new MybatisToolBindingStore(forbidden, codec, scheduler, clock, limits, c, r));
        assertThrows(IllegalArgumentException.class, () -> new MybatisToolBindingStore(forbidden, codec, scheduler, clock, limits, catalog, releases));
        assertEquals(0, count("binding"));
    }

    @Test
    public void createNamedBindingsAndReadImmutableRevisions() {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "first", object()), "create-1");
        var two = create(settings(v1, PINNED, "second", object()), "create-2");
        assertNotEquals(one.bindingId(), two.bindingId());
        assertEquals(one, first.find(owner, one.bindingId()).block(WAIT));
        var old = first.revision(owner, one.revision()).block(WAIT);
        var update = save(one, settings(v1, PINNED, "renamed", object("text", text("new"))), "save");
        assertEquals(2, update.result().value().version());
        assertEquals(old, first.revision(owner, one.revision()).block(WAIT));
        assertEquals(3, count("binding_revision"));
        assertEquals(0, count("binding_resolution"));
        assertNotNull(node.service);
    }

    @Test
    public void saveReplacesTheEntireBindingLayer() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object("text", text("old"))), "create");
        assertEquals(object("text", text("old")), resolve(one).effectiveConfiguration());
        save(one, settings(v1, PINNED, "one", object()), "save");
        assertEquals(object(), resolve(one).effectiveConfiguration());
        assertEquals(2, count("binding_resolution"));
    }

    @Test
    public void schemaValidationRejectsWrongTypeUnknownFieldsAndExplicitNull() {
        publishAll(v1);
        for (var config : List.of(object("text", StructuredValue.NullValue.INSTANCE), object("extra", text("x")), object("text", object()))) {
            var receipt = run(createCommand(settings(v1, PINNED, "invalid", config), UUID.randomUUID().toString()));
            assertEquals(CONFIGURATION_INVALID, receipt.result().rejection());
        }
        assertEquals(0, count("binding"));
        assertEquals(3, count("binding_command"));
    }

    @Test
    public void rejectsUtf8BytesBeyondServerConfigurationCapacity() {
        publishAll(v1);
        var small = store(policy(60, 10, 0, 20, 100_000, ToolExecutionPolicy.ApprovalMode.NONE));
        var result = small.execute(actor, owner, createCommand(settings(v1, PINNED, "bytes", object("text", text("中文中文中文中文中文中文中文中文"))), "bytes")).block(WAIT);
        assertEquals(CONFIGURATION_INVALID, result.value().result().rejection());
        assertEquals(0, count("binding"));
    }

    @Test
    public void completePlanMustFitStorageBeforeABindingCanBeSaved() {
        var original = v1.revision().tool();
        var withDefaults = new ToolDefinition(original.definition(), original.providerId(), original.title(), original.description(), original.tags(),
                original.capability(), original.inputSchema(), original.outputSchema(), original.configurationSchema(), object("text", text("d".repeat(400_000))),
                original.capabilities(), original.risk(), original.defaultPolicy(), "0".repeat(64));
        var fixture = new Fixture(v1.provider(), v1.schema(), new ToolDefinitionRevision(withTitle(withDefaults, withDefaults.title()), v1.provider().definition(), v1.revision().target()), v1.adapterRef());
        publishAll(fixture);
        var roomy = store(policy(60, 10, 0, 1_000_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE));
        var command = createCommand(settings(fixture, PINNED, "large", object("text", text("x".repeat(700_000)))), "too-large");
        assertEquals(CONFIGURATION_INVALID, roomy.execute(actor, owner, command).block(WAIT).value().result().rejection());
        assertEquals(0, count("binding"));
        assertEquals(0, count("binding_revision"));
        assertEquals(1, count("binding_command"));
    }

    @Test
    public void nonemptySecretReferencesAreExplicitlyUnsupported() {
        publishAll(v1);
        var s = settings(v1, PINNED, "secret", object());
        var withSecret = new ToolBindingSettings(s.tool(), s.versionPolicy(), s.name(), true, s.configuration(), Map.of("api", ref("secret", "key", "v1")), null, null);
        assertEquals(SECRET_UNSUPPORTED, run(createCommand(withSecret, "secret")).result().rejection());
        assertEquals(0, count("binding"));
    }

    @Test
    public void mayOnlyNarrowNetworkOrigins() {
        publishAll(v1);
        var s = settings(v1, PINNED, "network", object());
        var expanded = new ToolBindingSettings(s.tool(), s.versionPolicy(), s.name(), true, s.configuration(), Map.of(), null, Set.of(URI.create("https://example.com")));
        assertEquals(NETWORK_NOT_ALLOWED, run(createCommand(expanded, "network")).result().rejection());
        var empty = new ToolBindingSettings(s.tool(), s.versionPolicy(), s.name(), true, s.configuration(), Map.of(), null, Set.of());
        assertEquals(Set.of(), resolve(create(empty, "empty")).networkOrigins());
    }

    @Test
    public void limitsAndApprovalCannotBeWeakenedByBindingOverrides() {
        publishAll(v1);
        var bound = settings(v1, PINNED, "bounded", object());
        var requested = policy(600, 10, 2, 900_000, 900_000, ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL);
        var value = new ToolBindingSettings(bound.tool(), PINNED, bound.name(), true, object(), Map.of(), requested, null);
        var plan = resolve(create(value, "bounded"));
        assertEquals(policy(30, 1, 2, 1024, 1024, ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL), plan.effectivePolicy());
    }

    @Test
    public void contradictoryBackoffAndServerTimeoutAreRejected() {
        publishAll(v1);
        var small = store(policy(1, 10, 0, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE));
        var s = settings(v1, PINNED, "conflict", object());
        var invalid = new ToolBindingSettings(s.tool(), PINNED, s.name(), true, object(), Map.of(), policy(30, 1, 2, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE), null);
        assertEquals(POLICY_CONFLICT, small.execute(actor, owner, createCommand(invalid, "conflict")).block(WAIT).value().result().rejection());
    }

    @Test
    public void originalSuccessReplaysAfterLaterChangesWithoutCreatingRevisions() {
        publishAll(v1);
        var command = createCommand(settings(v1, PINNED, "one", object()), "create");
        var initial = first.execute(actor, owner, command).block(WAIT);
        var one = initial.value().result().value();
        save(one, settings(v1, PINNED, "two", object()), "save");
        var replay = second.execute(actor, owner, command).block(WAIT);
        assertEquals(StoreOutcome.Code.REPLAYED, replay.code());
        assertEquals(initial.value(), replay.value());
        assertEquals(1, replay.value().result().value().version());
        assertEquals(2, count("binding_revision"));
        var changed = first.execute(actor, owner, createCommand(settings(v1, PINNED, "changed", object()), "create")).block(WAIT);
        assertEquals(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, changed.code());
    }

    @Test
    public void rejectedDecisionsReplayEvenAfterTheToolBecomesPublished() {
        discover(v1);
        var command = createCommand(settings(v1, PINNED, "one", object()), "create");
        var rejected = first.execute(actor, owner, command).block(WAIT);
        assertEquals(TOOL_UNAVAILABLE, rejected.value().result().rejection());
        publishAll(v1);
        assertEquals(rejected.value(), first.execute(actor, owner, command).block(WAIT).value());
        assertEquals(0, count("binding"));
    }

    @Test
    public void concurrentSameCommandCreatesExactlyOneBindingRevisionAndReceipt() {
        publishAll(v1);
        var command = createCommand(settings(v1, PINNED, "one", object()), "same");
        var pair = Mono.zip(first.execute(actor, owner, command), second.execute(actor, owner, command)).block(WAIT);
        assertEquals(Set.of(StoreOutcome.Code.APPLIED, StoreOutcome.Code.REPLAYED), Set.of(pair.getT1().code(), pair.getT2().code()));
        assertEquals(pair.getT1().value(), pair.getT2().value());
        assertEquals(1, count("binding"));
        assertEquals(1, count("binding_revision"));
        assertEquals(1, count("binding_command"));
    }

    @Test
    public void concurrentDifferentUpdatesDoNotLoseOneAnother() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var pair = Mono.zip(first.execute(actor, owner, saveCommand(one, settings(v1, PINNED, "A", object()), "A")),
                second.execute(actor, owner, saveCommand(one, settings(v1, PINNED, "B", object()), "B"))).block(WAIT);
        assertEquals(Set.of(NONE, VERSION_CONFLICT), Set.of(pair.getT1().value().result().rejection(), pair.getT2().value().result().rejection()));
        assertEquals(2, first.find(owner, one.bindingId()).block(WAIT).version());
        assertEquals(2, count("binding_revision"));
        assertEquals(3, count("binding_command"));
    }

    @Test
    public void exactOwnerIsolationAndActorRulesApplyToEveryRead() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "private", object()), "create");
        var plan = resolve(one);
        var other = new ExecutionOwner("other-tenant", "workspace", "user");
        assertNull(first.find(other, one.bindingId()).block(WAIT));
        assertNull(first.revision(other, one.revision()).block(WAIT));
        assertNull(first.preview(other, one.bindingId(), 1, settings(v1, PINNED, "x", object())).block(WAIT));
        assertEquals(NOT_FOUND, first.resolve(other, one.bindingId()).block(WAIT).rejection());
        assertEquals(NOT_FOUND, first.fixed(other, plan.definition()).block(WAIT).rejection());
        assertTrue(first.list(other, null, 10).block(WAIT).items().isEmpty());
        assertTrue(first.history(other, one.bindingId(), null, 10).block(WAIT).items().isEmpty());
        var result = first.execute(actor, other, saveCommand(one, settings(v1, PINNED, "x", object()), "other")).block(WAIT).value().result();
        assertEquals(OWNER_MISMATCH, result.rejection());
        assertNull(result.currentVersion());
        assertThrows(IllegalArgumentException.class, () -> first.execute(actor("someone"), owner, createCommand(settings(v1, PINNED, "x", object()), "wrong-actor")));
    }

    @Test
    public void paginationIsOwnerScopedAndHasNoDuplicates() {
        publishAll(v1);
        for (int n = 0; n < 3; n++) create(settings(v1, PINNED, "name" + n, object()), "c" + n);
        var page1 = first.list(owner, null, 2).block(WAIT);
        var page2 = first.list(owner, page1.nextCursor(), 2).block(WAIT);
        assertEquals(2, page1.items().size());
        assertEquals(1, page2.items().size());
        assertNull(page2.nextCursor());
        assertNotEquals(page2.items().getFirst().bindingId(), page1.items().getFirst().bindingId());
        var one = page1.items().getFirst();
        save(one, settings(v1, PINNED, "renamed", object()), "rename");
        var history = first.history(owner, one.bindingId(), null, 1).block(WAIT);
        assertEquals(1, history.items().size());
        assertNotNull(history.nextCursor());
        assertEquals(1, first.history(owner, one.bindingId(), history.nextCursor(), 1).block(WAIT).items().size());
    }

    @Test
    public void stableBindingCannotChangeToolIdentityOrUpgradeThroughSave() {
        publishAll(v1, v2, fixture("other", "v1"));
        var one = create(settings(v1, PINNED, "one", object()), "create");
        assertEquals(USE_UPGRADE, save(one, settings(v2, PINNED, "one", object()), "version").result().rejection());
        assertEquals(TOOL_ID_MISMATCH, save(one, settings(fixture("other", "v1"), PINNED, "one", object()), "identity").result().rejection());
    }

    @Test
    public void pinnedResolutionDoesNotFollowReviewedSuccessors() {
        var node = discover(v1, v2);
        publish(node, v1, null);
        publish(node, v2, v1);
        var one = create(settings(v1, PINNED, "pinned", object()), "create");
        assertEquals(v1.revision(), resolve(one).tool());
    }

    @Test
    public void followUsesExplicitEdgesRatherThanLexicalVersionOrderAndDeduplicatesPlans() {
        var node = discover(v1, v2, v3);
        publish(node, v1, null);
        publish(node, v2, v1);
        publish(node, v3, v2);
        var one = create(settings(v1, FOLLOW_COMPATIBLE, "follow", object()), "create");
        var selected = resolve(one);
        assertEquals(v3.revision(), selected.tool());
        assertEquals(selected, resolve(one));
        assertEquals(1, count("binding_resolution"));
        assertEquals(one.revision(), selected.revision());
    }

    @Test
    public void unreviewedNewVersionIsNotAutomaticallySelected() {
        publishAll(v1, v2);
        var one = create(settings(v1, FOLLOW_COMPATIBLE, "follow", object()), "create");
        assertEquals(v1.revision(), resolve(one).tool());
    }

    @Test
    public void fixedPlansNeverReselectAfterNewPublicationOrBindingEdit() {
        var node = discover(v1, v2);
        publish(node, v1, null);
        var one = create(settings(v1, FOLLOW_COMPATIBLE, "follow", object("text", text("old"))), "create");
        var original = resolve(one);
        publish(node, v2, v1);
        assertEquals(v2.revision(), resolve(one).tool());
        save(one, settings(v1, FOLLOW_COMPATIBLE, "follow", object("text", text("new"))), "save");
        assertEquals(original, first.fixed(owner, original.definition()).block(WAIT).value());
        assertEquals(object("text", text("old")), original.effectiveConfiguration());
        assertNotEquals(original.definition(), resolve(one).definition());
    }

    @Test
    public void disabledIntermediateVersionCannotBeSkippedOrSilentlyFallBack() {
        var node = discover(v1, v2, v3);
        publish(node, v1, null);
        publish(node, v2, v1);
        publish(node, v3, v2);
        var one = create(settings(v1, FOLLOW_COMPATIBLE, "follow", object()), "create");
        release(node, v2, ToolReleaseCommand.Action.DISABLE, null);
        assertEquals(TOOL_UNAVAILABLE, first.resolve(owner, one.bindingId()).block(WAIT).rejection());
    }

    @Test
    public void overlongCompatibilityChainFailsClosedInsteadOfTruncating() {
        var fixtures = java.util.stream.IntStream.range(0, 128).mapToObj(i -> fixture("chain", "revision-" + i)).toArray(Fixture[]::new);
        var node = discover(fixtures);
        publish(node, fixtures[0], null);
        var one = create(settings(fixtures[0], FOLLOW_COMPATIBLE, "chain", object()), "create");
        for (int i = 1; i < fixtures.length; i++) publish(node, fixtures[i], fixtures[i - 1]);
        var last = fixture("chain", "revision-128");
        publish(discover(last), last, fixtures[127]);
        assertEquals(CHAIN_LIMIT_EXCEEDED, first.resolve(owner, one.bindingId()).block(WAIT).rejection());
        assertEquals(0, count("binding_resolution"));
    }

    @Test
    public void deprecationDeadlineAndNewBindingRulesAreEnforced() {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        release(node, v1, ToolReleaseCommand.Action.DEPRECATE, now.get().plusSeconds(10));
        assertEquals(TOOL_UNAVAILABLE, run(createCommand(settings(v1, PINNED, "new", object()), "new")).result().rejection());
        var original = resolve(one);
        now.set(now.get().plusSeconds(10));
        assertEquals(TOOL_UNAVAILABLE, first.resolve(owner, one.bindingId()).block(WAIT).rejection());
        assertEquals(TOOL_UNAVAILABLE, first.fixed(owner, original.definition()).block(WAIT).rejection());
    }

    @Test
    public void disableCopiesRevisionAndBlocksOldPlansEvenWhenDiscoveryFails() {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var original = resolve(one);
        var disabled = run(state(one, false, "disable")).result().value();
        assertEquals(2, disabled.version());
        assertEquals(first.revision(owner, one.revision()).block(WAIT).settings().withEnabled(false), first.revision(owner, disabled.revision()).block(WAIT).settings());
        assertEquals(BINDING_DISABLED, first.fixed(owner, original.definition()).block(WAIT).rejection());
        assertEquals(BINDING_DISABLED, first.resolve(owner, one.bindingId()).block(WAIT).rejection());
        release(node, v1, ToolReleaseCommand.Action.DISABLE, null);
        assertEquals(TOOL_UNAVAILABLE, run(state(disabled, true, "enable")).result().rejection());
    }

    @Test
    public void toolDisableBlocksOldPlanButBindingCanStillBeDisabled() {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var original = resolve(one);
        release(node, v1, ToolReleaseCommand.Action.DISABLE, null);
        assertEquals(TOOL_UNAVAILABLE, first.fixed(owner, original.definition()).block(WAIT).rejection());
        assertTrue(run(state(one, false, "disable")).result().successful());
    }

    @Test
    public void tighterCurrentServerPolicyRejectsAnOldFixedPlan() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var original = resolve(one);
        var stricter = store(policy(5, 1, 0, 100, 100, ToolExecutionPolicy.ApprovalMode.NONE));
        assertEquals(POLICY_CONFLICT, stricter.fixed(owner, original.definition()).block(WAIT).rejection());
        var updated = stricter.resolve(owner, one.bindingId()).block(WAIT).value();
        assertEquals(Duration.ofSeconds(5), updated.effectivePolicy().timeout());
        assertNotEquals(original.definition(), updated.definition());
    }

    @Test
    public void configurationCapacityIsIndependentOfArgumentLimitsAndRecheckedForFixedPlans() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "large-config", object("text", text("x".repeat(3000)))), "create");
        var plan = resolve(one);
        assertEquals(1024, plan.effectivePolicy().maxInputBytes());
        var stricter = store(policy(60, 10, 0, 2000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE));
        assertEquals(CONFIGURATION_INVALID, stricter.fixed(owner, plan.definition()).block(WAIT).rejection());
        assertEquals(CONFIGURATION_INVALID, stricter.resolve(owner, one.bindingId()).block(WAIT).rejection());
    }

    @Test
    public void upgradePreviewIsReadOnlyAndConfirmationCreatesOneRevision() {
        var node = discover(v1, v2);
        publish(node, v1, null);
        publish(node, v2, v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var proposal = settings(v2, PINNED, "upgraded", object("text", text("x")));
        var preview = first.preview(owner, one.bindingId(), 1, proposal).block(WAIT);
        assertTrue(preview.compatible());
        assertTrue(preview.proposed().successful());
        assertEquals(1, count("binding_revision"));
        assertEquals(0, count("binding_resolution"));
        assertTrue(preview.changes().contains(ToolBindingUpgradePreview.Change.TOOL_VERSION));
        var upgraded = run(upgrade(one, proposal, preview.digest(), false, "upgrade"));
        assertTrue(upgraded.result().successful());
        assertEquals(2, upgraded.result().value().version());
        assertEquals(v2.revision(), resolve(one).tool());
    }

    @Test
    public void unreviewedUpgradeRequiresExplicitAcknowledgement() {
        publishAll(v1, v2);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var proposal = settings(v2, PINNED, "two", object());
        var preview = first.preview(owner, one.bindingId(), 1, proposal).block(WAIT);
        assertFalse(preview.compatible());
        assertEquals(INCOMPATIBLE, run(upgrade(one, proposal, preview.digest(), false, "reject")).result().rejection());
        assertTrue(run(upgrade(one, proposal, preview.digest(), true, "accept")).result().successful());
    }

    @Test
    public void confirmationRecomputesPreviewAndRejectsChangedLifecycle() {
        var node = publishAll(v1, v2);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var proposal = settings(v2, PINNED, "two", object());
        var preview = first.preview(owner, one.bindingId(), 1, proposal).block(WAIT);
        release(node, v1, ToolReleaseCommand.Action.DEPRECATE, null);
        assertEquals(PREVIEW_CHANGED, run(upgrade(one, proposal, preview.digest(), true, "stale")).result().rejection());
        assertEquals(1, count("binding_revision"));
        assertEquals(VERSION_CONFLICT, first.preview(owner, one.bindingId(), 2, proposal).block(WAIT).proposed().rejection());
    }

    @Test
    public void explicitUpgradeCanRecoverFromDisabledOldBaseline() {
        var node = publishAll(v1, v2);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        release(node, v1, ToolReleaseCommand.Action.DISABLE, null);
        var proposal = settings(v2, PINNED, "two", object());
        var preview = first.preview(owner, one.bindingId(), 1, proposal).block(WAIT);
        assertTrue(preview.proposed().successful());
        assertTrue(run(upgrade(one, proposal, preview.digest(), true, "repair")).result().successful());
        assertEquals(v2.revision(), resolve(one).tool());
    }

    @Test
    public void auditInsertFailureRollsBackHeaderAndRevision() {
        publishAll(v1);
        jdbc.execute("ALTER TABLE arte_ai_tool_binding_command ADD CONSTRAINT fail_audit CHECK (length(snapshot) < 2)");
        assertThrows(RuntimeException.class, () -> run(createCommand(settings(v1, PINNED, "one", object()), "fail")));
        assertEquals(0, count("binding"));
        assertEquals(0, count("binding_revision"));
        assertEquals(0, count("binding_command"));
    }

    @Test
    public void snapshotsRoundTripAndStrictVersionsRejectWrongEnvelopeTypes() {
        publishAll(v1);
        var receipt = run(createCommand(settings(v1, PINNED, "one", object()), "create"));
        var one = receipt.result().value();
        var revision = first.revision(owner, one.revision()).block(WAIT);
        var plan = resolve(one);
        roundTrip(receipt, ToolBindingReceipt.class);
        roundTrip(one, ToolBindingDefinition.class);
        roundTrip(revision, ToolBindingRevision.class);
        roundTrip(plan, ToolBindingResolution.class);
        assertEquals(plan.definition(), plan.resolvedBinding().definition());
        assertNull(plan.resolvedBinding().rate());
        assertThrows(IllegalArgumentException.class, () -> first.fixed(owner, one.revision()));
        assertThrows(IllegalArgumentException.class, () -> first.revision(owner, plan.definition()));
    }

    @Test
    public void snapshotTamperingIsAStorageErrorRatherThanAUserRejection() {
        publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var plan = resolve(one);
        jdbc.update("UPDATE arte_ai_tool_binding_resolution SET payload_digest=?", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> first.fixed(owner, plan.definition()).block(WAIT));
    }

    @Test
    public void nativeServiceRequiresCurrentDiscoveryAndImplementationButNeverInvokes() {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var plan = node.service.resolve(owner, one.bindingId()).block(WAIT);
        assertTrue(plan.successful());
        var missing = new StaticToolRegistry(List.of(fixture("other", "v1").registration()));
        var unavailable = new ToolBindingService(first, node.discovery, missing, scheduler, Duration.ofSeconds(1));
        assertEquals(EXECUTOR_UNAVAILABLE, unavailable.resolve(owner, one.bindingId()).block(WAIT).rejection());
        assertEquals(EXECUTOR_UNAVAILABLE, unavailable.fixed(owner, plan.value().definition()).block(WAIT).rejection());
        var unknownDiscovery = new StaticToolDiscovery(node.registry, catalog, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(1));
        var unhealthy = new ToolBindingService(first, unknownDiscovery, node.registry, scheduler, Duration.ofSeconds(1));
        assertEquals(EXECUTOR_UNAVAILABLE, unhealthy.resolve(owner, one.bindingId()).block(WAIT).rejection());
        assertTrue(unhealthy.execute(actor, owner, state(one, false, "disable")).block(WAIT).value().result().successful());
    }

    @Test
    public void nativeResolutionRechecksLiveCapabilityAndBoundsMetadataReading() throws Exception {
        var node = publishAll(v1);
        var one = create(settings(v1, PINNED, "one", object()), "create");
        var live = new AtomicReference<>(v1.revision().tool().discoveredCapability());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocking = new java.util.concurrent.atomic.AtomicBoolean();
        BusinessToolAdapter adapter = new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                if (blocking.get()) {
                    entered.countDown();
                    try {
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return live.get();
            }

            public Mono<ToolResult> invoke(com.arte.ainew.pojo.execution.GatewayCall<ToolInvocation> call) {
                throw new AssertionError("No invoke");
            }
        };
        var registry = new StaticToolRegistry(List.of(new StaticToolRegistry.Registration(v1.provider(), () -> Mono.just(v1.definitions()), Map.of(v1.adapterRef(), adapter))));
        var service = new ToolBindingService(first, node.discovery, registry, scheduler, Duration.ofMillis(100));
        assertTrue(service.resolve(owner, one.bindingId()).block(WAIT).successful());
        var expected = live.get();
        live.set(new CapabilityDescriptor(expected.definition(), expected.kind(), expected.inputSchema(), expected.outputSchema(), expected.features(), expected.sideEffect(), CapabilityDescriptor.Availability.DISABLED));
        assertEquals(EXECUTOR_UNAVAILABLE, service.resolve(owner, one.bindingId()).block(WAIT).rejection());
        live.set(expected);
        blocking.set(true);
        try {
            assertEquals(EXECUTOR_UNAVAILABLE, service.resolve(owner, one.bindingId()).block(WAIT).rejection());
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void noChangeDoesNotManufactureAnotherRevisionAndMissingUpdatesAreAudited() {
        publishAll(v1);
        var settings = settings(v1, PINNED, "one", object());
        var one = create(settings, "create");
        assertEquals(NO_CHANGE, save(one, settings, "same").result().rejection());
        assertTrue(first.preview(owner, one.bindingId(), 1, settings).block(WAIT).proposed().successful());
        var missing = new ToolBindingCommand(ToolBindingCommand.Action.SAVE, "missing", 1, "missing", "reason", settings, null, null, false);
        assertEquals(NOT_FOUND, run(missing).result().rejection());
        assertEquals(1, count("binding_revision"));
        assertEquals(3, count("binding_command"));
    }

    @Test
    public void baselineAndIncrementalBindingDdlAreIdentical() throws Exception {
        var baseline = Files.readString(Path.of("scripts/arte-ai-new-ddl-mysql.sql"));
        var incremental = Files.readString(Path.of("scripts/arte-ai-tool-binding-migration.sql"));
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(), baseline.substring(baseline.indexOf("create table arte_ai_tool_binding\n"), baseline.indexOf("-- AI TOOL S1-08")).trim());
    }

    private MybatisToolBindingStore store(ToolExecutionPolicy policy) {
        return new MybatisToolBindingStore(ds, codec, scheduler, clock, policy, catalog, releases);
    }

    private record Node(StaticToolRegistry registry, StaticToolDiscovery discovery, ToolReleaseService release,
                        ToolBindingService service) {
    }

    private Node discover(Fixture... fixtures) {
        var groups = new LinkedHashMap<DefinitionRef, List<Fixture>>();
        for (var f : fixtures) groups.computeIfAbsent(f.provider().definition(), ignored -> new ArrayList<>()).add(f);
        var registrations = new ArrayList<StaticToolRegistry.Registration>();
        for (var group : groups.values()) {
            var schemas = new LinkedHashMap<DefinitionRef, ToolSchemaDefinition>();
            var tools = new ArrayList<ToolDefinitionRevision>();
            var adapters = new LinkedHashMap<DefinitionRef, BusinessToolAdapter>();
            for (var f : group) {
                schemas.put(f.schema().definition(), f.schema());
                tools.add(f.revision());
                adapters.put(f.adapterRef(), adapter(f.revision().tool().discoveredCapability()));
            }
            var definitions = new StaticToolDefinitionSource.Definitions(List.copyOf(schemas.values()), tools);
            registrations.add(new StaticToolRegistry.Registration(group.getFirst().provider(), () -> Mono.just(definitions), adapters));
        }
        var registry = new StaticToolRegistry(registrations);
        var discovery = new StaticToolDiscovery(registry, catalog, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(5));
        assertTrue(discovery.refresh().block(WAIT).state().discoveryHealthy());
        var validator = new StaticToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(5));
        return new Node(registry, discovery, new ToolReleaseService(releases, validator), new ToolBindingService(first, discovery, registry, scheduler, Duration.ofSeconds(2)));
    }

    private Node publishAll(Fixture... fixtures) {
        var node = discover(fixtures);
        for (var f : fixtures) publish(node, f, null);
        return node;
    }

    private void publish(Node node, Fixture fixture, Fixture predecessor) {
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object(), object())), "risk-review", null,
                fixture.revision().tool().defaultPolicy().approvalMode(), predecessor == null ? null : predecessor.revision().tool().definition(), predecessor == null ? null : "reviewed-compatibility");
        var command = new ToolReleaseCommand(fixture.revision().tool().definition(), ToolReleaseCommand.Action.PUBLISH, 0, "publish-" + fixture.revision().tool().definition().id() + "-" + fixture.revision().tool().definition().version(), "reason", review, null);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, node.release.execute(actor, command).block(WAIT).value().rejection());
    }

    private void release(Node node, Fixture fixture, ToolReleaseCommand.Action action, Instant deadline) {
        var current = releases.find(fixture.revision().tool().definition()).block(WAIT);
        var command = new ToolReleaseCommand(current.tool(), action, current.version(), action.name() + current.tool().version() + current.version(), "reason", null, deadline);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, node.release.execute(actor, command).block(WAIT).value().rejection());
    }

    private static TrustedActor actor(String id) {
        return new TrustedActor(new Principal(id, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), START);
    }

    private static ToolBindingSettings settings(Fixture fixture, ToolBindingSettings.VersionPolicy policy, String name, StructuredValue.ObjectValue config) {
        return new ToolBindingSettings(fixture.revision().tool().definition(), policy, name, true, config, Map.of(), null, null);
    }

    private static ToolBindingCommand createCommand(ToolBindingSettings settings, String key) {
        return new ToolBindingCommand(ToolBindingCommand.Action.SAVE, null, 0, key, "reason", settings, null, null, false);
    }

    private static ToolBindingCommand saveCommand(ToolBindingDefinition one, ToolBindingSettings settings, String key) {
        return new ToolBindingCommand(ToolBindingCommand.Action.SAVE, one.bindingId(), one.version(), key, "reason", settings, null, null, false);
    }

    private static ToolBindingCommand state(ToolBindingDefinition one, boolean enabled, String key) {
        return new ToolBindingCommand(ToolBindingCommand.Action.SET_ENABLED, one.bindingId(), one.version(), key, "reason", null, enabled, null, false);
    }

    private static ToolBindingCommand upgrade(ToolBindingDefinition one, ToolBindingSettings settings, String digest, boolean accept, String key) {
        return new ToolBindingCommand(ToolBindingCommand.Action.UPGRADE, one.bindingId(), one.version(), key, "reason", settings, null, digest, accept);
    }

    private ToolBindingDefinition create(ToolBindingSettings settings, String key) {
        var result = run(createCommand(settings, key)).result();
        assertEquals(NONE, result.rejection());
        return result.value();
    }

    private ToolBindingReceipt run(ToolBindingCommand command) {
        var result = first.execute(actor, owner, command).block(WAIT);
        assertEquals(StoreOutcome.Code.APPLIED, result.code());
        return result.value();
    }

    private ToolBindingReceipt save(ToolBindingDefinition one, ToolBindingSettings settings, String key) {
        return run(saveCommand(one, settings, key));
    }

    private ToolBindingResolution resolve(ToolBindingDefinition one) {
        var result = first.resolve(owner, one.bindingId()).block(WAIT);
        assertEquals(NONE, result.rejection());
        return result.value();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_" + table, Integer.class);
    }

    private <T> void roundTrip(T value, Class<T> type) {
        String json = codec.encode(value);
        assertEquals(value, codec.decode(json, type));
        for (var replacement : List.of("\"1\"", "1.2", "true", "null", "2"))
            assertThrows(RuntimeException.class,
                    () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":" + replacement), type));
    }
}

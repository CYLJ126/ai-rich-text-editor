package com.arte.ainew.tool.release;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.persistence.mybatis.MybatisToolReleaseStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolRelease.State.*;
import static com.arte.ainew.pojo.tool.ToolReleaseCommand.Action.*;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 临时 H2 与真实 MyBatis 事务，验证生命周期、预检、兼容边、防重及审计回滚；不实际执行工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public class ToolReleaseTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Instant START = Instant.parse("2026-10-18T01:02:03.123456Z");
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
    private final Fixture tool = fixture("text", "v1");
    private final TrustedActor actor = actor("admin");
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private Connection ddlConnection;
    private MybatisToolCatalogStore catalog;
    private MybatisToolReleaseStore first, second;

    @Before
    public void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        // H2 的 IN 常量检查保留创建会话；保持 DDL 连接存活，事务操作仍使用其他独立连接。
        ddlConnection = dataSource.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).populate(ddlConnection);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE arte_ai_tool_release_command");
        jdbc.execute("DROP TABLE arte_ai_tool_release");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-tool-release-migration.sql")).populate(ddlConnection);
        scheduler = Schedulers.newBoundedElastic(4, 64, "tool-release-test");
        catalog = new MybatisToolCatalogStore(dataSource, codec, scheduler, clock);
        first = new MybatisToolReleaseStore(dataSource, codec, scheduler, clock);
        second = new MybatisToolReleaseStore(dataSource, codec, scheduler, clock);
    }

    @After
    public void cleanup() throws Exception {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
        ddlConnection.close();
    }

    @Test
    public void constructionDoesNotConnectOrCreateTables() {
        var forbidden = new DelegatingDataSource(dataSource) {
            public Connection getConnection() {
                throw new AssertionError("No startup SQL");
            }

            public Connection getConnection(String user, String password) {
                return getConnection();
            }
        };
        assertNotNull(new MybatisToolReleaseStore(forbidden, codec, scheduler, clock));
        assertEquals(0, count("release"));
    }

    @Test
    public void registeredDefinitionsAreDraftWithoutWritingGovernanceRows() {
        var node = discoverNode(tool);
        var draft = first.find(tool.revision().tool().definition()).block(WAIT);
        assertEquals(ToolRelease.draft(tool.revision().tool().definition()), draft);
        assertFalse(draft.permitsNewBinding());
        assertFalse(draft.permitsExistingBinding(now.get()));
        assertEquals(0, count("release"));
        assertEquals(0, count("release_command"));
        assertNull(first.find(ref("tool", "missing.tool", "v1")).block(WAIT));
        assertTrue(node.discovery.state().discoveryHealthy());
    }

    @Test
    public void publicationDeprecationDisableAndReenablePreserveTheFirstContract() {
        var node = discoverNode(tool);
        var published = publish(node, tool, "publish");
        assertEquals(PUBLISHED, published.release().state());
        assertEquals(1, published.release().version());
        assertTrue(published.release().permitsNewBinding());
        var deprecated = run(node, command(tool, DEPRECATE, 1, "deprecate"));
        assertEquals(DEPRECATED, deprecated.release().state());
        assertFalse(deprecated.release().permitsNewBinding());
        assertTrue(deprecated.release().permitsExistingBinding(now.get()));
        var disabled = run(node, command(tool, DISABLE, 2, "disable"));
        assertEquals(DISABLED, disabled.release().state());
        assertFalse(disabled.release().permitsExistingBinding(now.get()));
        var restored = run(node, command(tool, REENABLE, 3, "restore"));
        assertEquals(DEPRECATED, restored.release().state());
        assertFalse(restored.release().permitsNewBinding());
        assertEquals(published.release().publication(), restored.release().publication());
        assertEquals(4, count("release_command"));
        assertEquals(restored.release(), second.find(tool.revision().tool().definition()).block(WAIT));
    }

    @Test
    public void deprecatedDeadlineIsExclusiveAndCannotBeExtendedByReenable() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        var until = START.plusSeconds(60);
        var deprecate = new ToolReleaseCommand(tool.revision().tool().definition(), DEPRECATE, 1, "deprecate", "sunset", null, until);
        var release = run(node, deprecate).release();
        assertTrue(release.permitsExistingBinding(until.minusNanos(1)));
        assertFalse(release.permitsExistingBinding(until));
        run(node, command(tool, DISABLE, 2, "disable"));
        now.set(until);
        var rejected = run(node, command(tool, REENABLE, 3, "restore"));
        assertEquals(ToolReleaseReceipt.Rejection.EXPIRED, rejected.rejection());
        assertEquals(DISABLED, rejected.release().state());
        assertEquals(3, rejected.release().version());
    }

    @Test
    public void pastDeprecationDeadlineIsRejectedWithoutChangingState() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        var command = new ToolReleaseCommand(tool.revision().tool().definition(), DEPRECATE, 1, "past", "sunset", null, START);
        assertEquals(ToolReleaseReceipt.Rejection.EXPIRED, run(node, command).rejection());
        assertEquals(PUBLISHED, first.find(command.tool()).block(WAIT).state());
    }

    @Test
    public void replayReturnsTheOriginalReceiptEvenAfterDisableOrFailedDiscovery() {
        var node = discoverNode(tool);
        var command = publication(tool, "publish", review(tool, null));
        var original = node.service.execute(actor, command).block(WAIT);
        run(node, command(tool, DISABLE, 1, "disable"));
        var replay = node.service.execute(actor, command).block(WAIT);
        assertEquals(REPLAYED, replay.code());
        assertEquals(original.value(), replay.value());
        assertEquals(PUBLISHED, replay.value().release().state());
        assertEquals(DISABLED, first.find(command.tool()).block(WAIT).state());
        assertEquals(2, count("release_command"));
    }

    @Test
    public void changedCommandCannotReuseAnActorKeyAcrossActionsOrTools() {
        var other = fixture("other", "v1");
        var node = discoverNode(tool, other);
        publish(node, tool, "shared");
        assertEquals(IDEMPOTENCY_CONFLICT, node.service.execute(actor, command(tool, DISABLE, 1, "shared")).block(WAIT).code());
        assertEquals(IDEMPOTENCY_CONFLICT, node.service.execute(actor, publication(other, "shared", review(other, null))).block(WAIT).code());
        assertEquals(1, count("release_command"));
    }

    @Test
    public void differentActualActorsHaveIndependentKeysAndAuditIdentities() {
        var other = fixture("other", "v1");
        var node = discoverNode(tool, other);
        publish(node, tool, "shared");
        var receipt = node.service.execute(actor("another"), publication(other, "shared", review(other, null))).block(WAIT).value();
        assertEquals("another", receipt.actorSubjectId());
        assertEquals(PrincipalKindEnum.USER, receipt.actorKind());
        assertEquals(APPLIED, receipt.outcome());
        assertEquals(2, count("release_command"));
    }

    @Test
    public void expectedVersionIsCheckedBeforeAnyStateChangeAndRejectionIsDurable() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        var stale = command(tool, DISABLE, 0, "stale");
        var rejected = run(node, stale);
        assertEquals(StoreOutcome.Code.VERSION_CONFLICT, rejected.outcome());
        run(node, command(tool, DISABLE, 1, "real"));
        var replay = node.service.execute(actor, stale).block(WAIT);
        assertEquals(REPLAYED, replay.code());
        assertEquals(rejected, replay.value());
        assertEquals(1, replay.value().release().version());
        assertEquals(2, first.find(stale.tool()).block(WAIT).version());
    }

    @Test
    public void missingToolRejectionCannotTurnIntoAPublicationOnRetry() {
        var registry = new LocalToolRegistry(List.of(tool.registration()));
        var discovery = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), scheduler, clock, WAIT.dividedBy(3));
        var validator = new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock, WAIT.dividedBy(3));
        var service = new ToolReleaseService(first, validator);
        var command = publication(tool, "missing", review(tool, null));
        var missing = service.execute(actor, command).block(WAIT).value();
        assertEquals(NOT_FOUND, missing.outcome());
        discovery.refresh().block(WAIT);
        assertEquals(missing, service.execute(actor, command).block(WAIT).value());
        assertEquals(DRAFT, first.find(command.tool()).block(WAIT).state());
    }

    @Test
    public void twoInstancesPublishTheSameCommandOnlyOnce() {
        var node = discoverNode(tool);
        var command = publication(tool, "same", review(tool, null));
        var validation = node.validator.validate(command.tool(), command.review()).block(WAIT);
        var results = Mono.zip(first.execute(actor, command, validation), second.execute(actor, command, validation)).block(WAIT);
        assertEquals(Set.of(APPLIED, REPLAYED), Set.of(results.getT1().code(), results.getT2().code()));
        assertEquals(results.getT1().value(), results.getT2().value());
        assertEquals(1, count("release"));
        assertEquals(1, count("release_command"));
    }

    @Test
    public void competingStateChangesHaveOnlyOneVersionWinner() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        var results = Mono.zip(first.execute(actor, command(tool, DISABLE, 1, "disable"), null),
                second.execute(actor, command(tool, DEPRECATE, 1, "deprecate"), null)).block(WAIT);
        assertEquals(Set.of(APPLIED, StoreOutcome.Code.VERSION_CONFLICT),
                Set.of(results.getT1().value().outcome(), results.getT2().value().outcome()));
        assertEquals(2, first.find(tool.revision().tool().definition()).block(WAIT).version());
        assertEquals(3, count("release_command"));
    }

    @Test
    public void anUnhealthyNodeCanStillDisableAndDeprecateButCannotReenable() {
        var broken = new AtomicReference<>(false);
        var registration = tool.registration(() -> broken.get() ? Mono.error(new IllegalStateException("private-source")) : Mono.just(tool.definitions()));
        var node = node(new LocalToolRegistry(List.of(registration)));
        node.discovery.refresh().block(WAIT);
        publish(node, tool, "publish");
        broken.set(true);
        node.discovery.refresh().block(WAIT);
        assertEquals(APPLIED, run(node, command(tool, DEPRECATE, 1, "deprecate")).outcome());
        assertEquals(APPLIED, run(node, command(tool, DISABLE, 2, "disable")).outcome());
        var rejected = run(node, command(tool, REENABLE, 3, "restore"));
        assertEquals(INVALID_STATE, rejected.outcome());
        assertEquals(List.of(ToolPublicationValidation.Issue.DISCOVERY_UNHEALTHY), rejected.validation().issues());
    }

    @Test
    public void publicationAndReenableCheckTheCurrentRegisteredImplementation() {
        var current = new AtomicReference<>(tool.revision().tool().discoveredCapability());
        var implementation = new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                return current.get();
            }

            public Mono<ToolResult> invoke(com.arte.ainew.pojo.execution.GatewayCall<ToolInvocation> call) {
                throw new AssertionError("No tool execution during publication");
            }
        };
        var registration = new LocalToolRegistry.Registration(tool.provider(), () -> Mono.just(tool.definitions()), Map.of(tool.adapterRef(), implementation));
        var node = node(new LocalToolRegistry(List.of(registration)));
        node.discovery.refresh().block(WAIT);
        var capability = current.get();
        current.set(new CapabilityDescriptor(capability.definition(), capability.kind(), capability.inputSchema(), capability.outputSchema(),
                capability.features(), capability.sideEffect(), CapabilityDescriptor.Availability.DISABLED));
        var rejected = run(node, publication(tool, "bad", review(tool, null)));
        assertEquals(List.of(ToolPublicationValidation.Issue.IMPLEMENTATION_MISMATCH), rejected.validation().issues());
        current.set(capability);
        var publication = publish(node, tool, "publish").release().publication();
        run(node, command(tool, DISABLE, 1, "disable"));
        current.set(null);
        assertEquals(INVALID_STATE, run(node, command(tool, REENABLE, 2, "bad-restore")).outcome());
        current.set(capability);
        assertEquals(publication, run(node, command(tool, REENABLE, 2, "restore")).release().publication());
    }

    @Test
    public void examplesAreCheckedWithoutInvokingTheToolAndFailuresAreSafe() {
        var node = discoverNode(tool);
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object("secret-field", text("private-value")), object())),
                "reviewed", null, ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        var receipt = run(node, publication(tool, "bad", review));
        assertEquals(List.of(ToolPublicationValidation.Issue.EXAMPLE_INVALID), receipt.validation().issues());
        assertEquals(DRAFT, receipt.release().state());
        assertFalse(receipt.validation().toString().contains("private-value"));
    }

    @Test
    public void unknownRiskAndUnverifiedIdempotencyCannotBePublished() {
        for (var risk : List.of(new ToolRiskProfile(CapabilityDescriptor.SideEffect.UNKNOWN, false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of()),
                new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.UNVERIFIED, Set.of(), Set.of()))) {
            var base = fixture("risk" + risk.sideEffect().name(), "v1");
            var revised = withRisk(base, risk, ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL);
            var node = discoverNode(revised);
            var receipt = run(node, publication(revised, "risk" + risk.sideEffect(), review(revised, null)));
            assertEquals(INVALID_STATE, receipt.outcome());
            assertFalse(receipt.validation().valid());
        }
    }

    @Test
    public void operationKeyIdempotencyNeedsExplicitReviewEvidence() {
        var risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.DOMAIN_OPERATION_KEY, Set.of(), Set.of());
        var revised = withRisk(tool, risk, ToolExecutionPolicy.ApprovalMode.NONE);
        var node = discoverNode(revised);
        assertEquals(List.of(ToolPublicationValidation.Issue.IDEMPOTENCY_UNVERIFIED),
                run(node, publication(revised, "no-evidence", review(revised, null))).validation().issues());
        var review = new ToolReleaseCommand.Review(review(revised, null).examples(), "reviewed", "domain-operation-key-test-report",
                ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        assertEquals(APPLIED, run(node, publication(revised, "with-evidence", review)).outcome());
    }

    @Test
    public void externalEffectsRequireIndependentApprovalAtPublication() {
        var risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.EXTERNAL_EFFECT, false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of());
        var revised = withRisk(tool, risk, ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
        var node = discoverNode(revised);
        assertEquals(List.of(ToolPublicationValidation.Issue.POLICY_TOO_WEAK),
                run(node, publication(revised, "weak", review(revised, null))).validation().issues());
    }

    @Test
    public void capabilitiesWithoutAnExecutionProtocolRemainDiscoverableButUnpublishable() {
        for (var capabilities : List.of(new ToolCapabilities(Set.of(CapabilityDescriptor.Feature.CANCEL), false),
                new ToolCapabilities(Set.of(), true))) {
            var base = fixture("feature" + capabilities.outcomeQuerySupported(), "v1");
            var t = base.revision().tool();
            var product = new ToolDefinition(t.definition(), t.providerId(), t.title(), t.description(), t.tags(), t.capability(), t.inputSchema(),
                    t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), capabilities, t.risk(), t.defaultPolicy(), "0".repeat(64));
            var revised = new Fixture(base.provider(), base.schema(), new ToolDefinitionRevision(withTitle(product, product.title()),
                    base.provider().definition(), base.revision().target()), base.adapterRef());
            var node = discoverNode(revised);
            assertEquals(List.of(ToolPublicationValidation.Issue.UNSUPPORTED_CAPABILITY),
                    run(node, publication(revised, "unsupported" + capabilities.outcomeQuerySupported(), review(revised, null))).validation().issues());
        }
    }

    @Test
    public void sampleBytesHaveAPublicationLimitEvenWhenInvocationAllowsLargerInput() {
        var t = tool.revision().tool();
        var p = t.defaultPolicy();
        var product = new ToolDefinition(t.definition(), t.providerId(), t.title(), t.description(), t.tags(), t.capability(), t.inputSchema(),
                t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), t.capabilities(), t.risk(),
                new ToolExecutionPolicy(p.timeout(), p.maxAttempts(), p.retryBackoff(), 1_000_000, 1_000_000, p.approvalMode()), "0".repeat(64));
        var revised = new Fixture(tool.provider(), tool.schema(), new ToolDefinitionRevision(withTitle(product, product.title()),
                tool.provider().definition(), tool.revision().target()), tool.adapterRef());
        var node = discoverNode(revised);
        var examples = List.of(new ToolReleaseCommand.Example(object("text", text("中".repeat(12_000))), object()));
        var review = new ToolReleaseCommand.Review(examples, "reviewed", null, ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        assertEquals(List.of(ToolPublicationValidation.Issue.EXAMPLE_INVALID), run(node, publication(revised, "too-large", review)).validation().issues());
    }

    @Test
    public void staleOrDifferentContractAttestationCannotPublish() {
        var node = discoverNode(tool);
        var command = publication(tool, "old", review(tool, null));
        var proof = node.validator.validate(command.tool(), command.review()).block(WAIT);
        now.set(START.plusSeconds(61));
        assertEquals(ToolReleaseReceipt.Rejection.VALIDATION_FAILED, first.execute(actor, command, proof).block(WAIT).value().rejection());
        var forged = new ToolPublicationValidation("0".repeat(64), proof.discoveryChecksum(), List.of(), now.get());
        assertEquals(ToolReleaseReceipt.Rejection.CONTRACT_CHANGED,
                first.execute(actor, publication(tool, "forged", command.review()), forged).block(WAIT).value().rejection());
        assertEquals(0, count("release"));
    }

    @Test
    public void forgedSuccessfulPrecheckCannotBypassExampleValidationInTheTransaction() {
        var node = discoverNode(tool);
        var proof = node.validator.validate(tool.revision().tool().definition(), review(tool, null)).block(WAIT);
        var invalid = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object("missing", text("x")), object())),
                "reviewed", null, ToolExecutionPolicy.ApprovalMode.NONE, null, null);
        assertEquals(ToolReleaseReceipt.Rejection.VALIDATION_FAILED,
                first.execute(actor, publication(tool, "forged", invalid), proof).block(WAIT).value().rejection());
    }

    @Test
    public void failedAuditInsertRollsBackAStateChangeAndCommandKeyCanRetry() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        var original = first.find(tool.revision().tool().definition()).block(WAIT);
        jdbc.execute("ALTER TABLE arte_ai_tool_release_command ADD CONSTRAINT injected_failure CHECK (action <> 'DISABLE')");
        var command = command(tool, DISABLE, 1, "retry");
        assertThrows(RuntimeException.class, () -> node.service.execute(actor, command).block(WAIT));
        assertEquals(original, second.find(command.tool()).block(WAIT));
        assertEquals(1, count("release_command"));
        jdbc.execute("ALTER TABLE arte_ai_tool_release_command DROP CONSTRAINT injected_failure");
        assertEquals(APPLIED, run(node, command).outcome());
    }

    @Test
    public void failedFirstAuditRollsBackThePublicationRowAndCompatibilityReservation() {
        var node = discoverNode(tool);
        jdbc.execute("ALTER TABLE arte_ai_tool_release_command ADD CONSTRAINT injected_failure CHECK (action <> 'PUBLISH')");
        assertThrows(RuntimeException.class, () -> publish(node, tool, "publish"));
        assertEquals(0, count("release"));
        assertEquals(0, count("release_command"));
        assertEquals(DRAFT, first.find(tool.revision().tool().definition()).block(WAIT).state());
    }

    @Test
    public void compatibilityUsesAnExplicitEdgeWithoutVersionSortingAndCannotFork() {
        var ten = fixture("text", "v10");
        var two = fixture("text", "v2");
        var branch = fixture("text", "V1");
        var node = discoverNode(ten, two, branch);
        publish(node, ten, "ten");
        assertEquals(APPLIED, run(node, publication(two, "two", review(two, ten.revision().tool().definition()))).outcome());
        var rejected = run(node, publication(branch, "branch", review(branch, ten.revision().tool().definition())));
        assertEquals(ToolReleaseReceipt.Rejection.AMBIGUOUS_COMPATIBILITY, rejected.rejection());
        var current = first.find(two.revision().tool().definition()).block(WAIT);
        run(node, command(two, DISABLE, 1, "disable"));
        assertEquals(current.publication(), run(node, command(two, REENABLE, 2, "restore")).release().publication());
    }

    @Test
    public void compatibilityCannotUseAnUnpublishedPredecessorOrRewriteAnExistingEdge() {
        var two = fixture("text", "v2");
        var node = discoverNode(tool, two);
        assertEquals(ToolReleaseReceipt.Rejection.INCOMPATIBLE,
                run(node, publication(two, "draft-parent", review(two, tool.revision().tool().definition()))).rejection());
        publish(node, tool, "first");
        run(node, publication(two, "second", review(two, tool.revision().tool().definition())));
        var backwards = publication(tool, "backwards", review(tool, two.revision().tool().definition()));
        var stale = new ToolReleaseCommand(backwards.tool(), PUBLISH, 1, backwards.idempotencyKey(), backwards.reason(), backwards.review(), null);
        assertEquals(ToolReleaseReceipt.Rejection.INVALID_TRANSITION, run(node, stale).rejection());
    }

    @Test
    public void databaseConstraintsAlsoRejectCompatibilityForksAndMissingDefinitions() {
        var two = fixture("text", "v2");
        var three = fixture("text", "v3");
        var node = discoverNode(tool, two, three);
        publish(node, tool, "first");
        run(node, publication(two, "second", review(two, tool.revision().tool().definition())));
        String source = CanonicalJson.key("tool", "text.count", "v2");
        String target = CanonicalJson.key("tool", "text.count", "v3");
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO arte_ai_tool_release " +
                "SELECT ?,compatible_from_key,row_version,state,payload_digest,release_snapshot,updated_micros FROM arte_ai_tool_release WHERE tool_key=?", target, source));
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO arte_ai_tool_release " +
                "SELECT ?,NULL,row_version,state,payload_digest,release_snapshot,updated_micros FROM arte_ai_tool_release WHERE tool_key=?", "0".repeat(64), source));
        assertEquals(2, count("release"));
    }

    @Test
    public void incompatibleSchemaCannotBeDeclaredCompatibleEvenWithManualEvidence() {
        var two = fixture("text", "v2");
        var newSchema = schema(ref("schema", "text-next", "v1"), object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object")));
        var t = two.revision().tool();
        var revised = new ToolDefinition(t.definition(), t.providerId(), t.title(), t.description(), t.tags(), t.capability(),
                newSchema.definition(), newSchema.definition(), newSchema.definition(), t.defaultConfiguration(), t.capabilities(), t.risk(), t.defaultPolicy(), "0".repeat(64));
        var changed = new Fixture(two.provider(), newSchema, new ToolDefinitionRevision(withTitle(revised, revised.title()), two.provider().definition(), two.revision().target()), two.adapterRef());
        var node = discoverNode(tool, changed);
        publish(node, tool, "first");
        assertEquals(ToolReleaseReceipt.Rejection.INCOMPATIBLE,
                run(node, publication(changed, "incompatible", review(changed, tool.revision().tool().definition()))).rejection());
        assertEquals(APPLIED, publish(node, changed, "explicit-version").outcome());
    }

    @Test
    public void governanceSnapshotAndAuditIndexesAreVerifiedOnRead() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        jdbc.execute("UPDATE arte_ai_tool_release SET row_version=99");
        assertThrows(IllegalStateException.class, () -> first.find(tool.revision().tool().definition()).block(WAIT));
        jdbc.execute("UPDATE arte_ai_tool_release SET row_version=1");
        jdbc.execute("UPDATE arte_ai_tool_release_command SET request_digest='" + "0".repeat(64) + "'");
        assertThrows(IllegalStateException.class, () -> first.history(tool.revision().tool().definition(), null, 10).block(WAIT));
    }

    @Test
    public void historyPaginationRetainsTheOriginalActorReasonAndDecision() {
        var node = discoverNode(tool);
        publish(node, tool, "publish");
        run(node, command(tool, DEPRECATE, 1, "deprecate"));
        run(node, command(tool, DISABLE, 2, "disable"));
        var ids = new HashSet<String>();
        String cursor = null;
        do {
            var page = second.history(tool.revision().tool().definition(), cursor, 1).block(WAIT);
            for (var receipt : page.items()) {
                assertTrue(ids.add(receipt.commandId()));
                assertEquals("admin", receipt.actorSubjectId());
                assertFalse(receipt.command().reason().isBlank());
                assertEquals(APPLIED, receipt.outcome());
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(3, ids.size());
        assertThrows(IllegalArgumentException.class, () -> first.history(tool.revision().tool().definition(), null, 101));
    }

    @Test
    public void newRootAliasesAreClosedAndOldDefinitionEncodingStillRoundTrips() {
        var node = discoverNode(tool);
        var receipt = publish(node, tool, "publish");
        assertEquals(receipt, codec.decode(codec.encode(receipt), ToolReleaseReceipt.class));
        assertEquals(receipt.release(), codec.decode(codec.encode(receipt.release()), ToolRelease.class));
        assertEquals(tool.revision(), codec.decode(codec.encode(tool.revision()), ToolDefinitionRevision.class));
        var json = codec.encode(receipt);
        assertThrows(RuntimeException.class, () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":99"), ToolReleaseReceipt.class));
        assertThrows(RuntimeException.class, () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":1.2"), ToolReleaseReceipt.class));
        assertThrows(RuntimeException.class, () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), ToolReleaseReceipt.class));
        assertThrows(RuntimeException.class, () -> codec.decode(json, ToolRelease.class));
    }

    @Test
    public void inactiveActorsAndIncompleteCommandsAreRejectedBeforeWriting() {
        var node = discoverNode(tool);
        var inactive = new TrustedActor(new Principal("admin", PrincipalKindEnum.USER, PrincipalStatusEnum.SUSPENDED), START);
        assertThrows(IllegalArgumentException.class, () -> first.execute(inactive, publication(tool, "publish", review(tool, null)), null));
        assertThrows(IllegalArgumentException.class, () -> new ToolReleaseCommand(tool.revision().tool().definition(), PUBLISH, 0, "key", "reason", null, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolReleaseCommand(tool.revision().tool().definition(), DISABLE, -1, "key", "reason", null, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolReleaseCommand(tool.revision().tool().definition(), DISABLE, 0, "key", " ", null, null));
        assertEquals(0, count("release_command"));
    }

    @Test
    public void baselineAndIncrementalReleaseDdlAreIdentical() throws Exception {
        var baseline = Files.readString(Path.of("scripts/arte-ai-new-ddl-mysql.sql"));
        var incremental = Files.readString(Path.of("scripts/arte-ai-tool-release-migration.sql"));
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(),
                baseline.substring(baseline.indexOf("create table arte_ai_tool_release\n"), baseline.indexOf("-- AI TOOL S1-07")).trim());
    }

    private record Node(LocalToolDiscovery discovery, LocalToolPublicationValidator validator,
                        ToolReleaseService service) {
    }

    private Node discoverNode(Fixture... fixtures) {
        var groups = new LinkedHashMap<DefinitionRef, List<Fixture>>();
        for (var fixture : fixtures)
            groups.computeIfAbsent(fixture.provider().definition(), ref -> new ArrayList<>()).add(fixture);
        var registrations = new ArrayList<LocalToolRegistry.Registration>();
        groups.values().forEach(group -> {
            var schemas = new LinkedHashMap<DefinitionRef, ToolSchemaDefinition>();
            var tools = new ArrayList<ToolDefinitionRevision>();
            var adapters = new HashMap<DefinitionRef, BusinessToolAdapter>();
            for (var fixture : group) {
                schemas.put(fixture.schema().definition(), fixture.schema());
                tools.add(fixture.revision());
                adapters.put(fixture.adapterRef(), adapter(fixture.revision().tool().discoveredCapability()));
            }
            var definitions = new LocalToolDefinitionSource.Definitions(List.copyOf(schemas.values()), tools);
            registrations.add(new LocalToolRegistry.Registration(group.getFirst().provider(), () -> Mono.just(definitions), adapters));
        });
        var node = node(new LocalToolRegistry(registrations));
        assertTrue(node.discovery.refresh().block(WAIT).state().discoveryHealthy());
        return node;
    }

    private Node node(LocalToolRegistry registry) {
        var discovery = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(5));
        var validator = new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), scheduler, clock, Duration.ofSeconds(5));
        return new Node(discovery, validator, new ToolReleaseService(first, validator));
    }

    private ToolReleaseReceipt publish(Node node, Fixture fixture, String key) {
        return run(node, publication(fixture, key, review(fixture, null)));
    }

    private ToolReleaseReceipt run(Node node, ToolReleaseCommand command) {
        var result = node.service.execute(actor, command).block(WAIT);
        assertEquals(APPLIED, result.code());
        return result.value();
    }

    private static ToolReleaseCommand publication(Fixture fixture, String key, ToolReleaseCommand.Review review) {
        return new ToolReleaseCommand(fixture.revision().tool().definition(), PUBLISH, 0, key, "reviewed publication", review, null);
    }

    private static ToolReleaseCommand command(Fixture fixture, ToolReleaseCommand.Action action, long version, String key) {
        return new ToolReleaseCommand(fixture.revision().tool().definition(), action, version, key, "managed change", null, null);
    }

    private static ToolReleaseCommand.Review review(Fixture fixture, DefinitionRef from) {
        return new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object(), object())), "risk-review-report", null,
                fixture.revision().tool().defaultPolicy().approvalMode(), from, from == null ? null : "semantic-compatibility-review");
    }

    private static TrustedActor actor(String subject) {
        return new TrustedActor(new Principal(subject, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), START);
    }

    private static Fixture withRisk(Fixture original, ToolRiskProfile risk, ToolExecutionPolicy.ApprovalMode approval) {
        var tool = original.revision().tool();
        var policy = tool.defaultPolicy();
        var revised = new ToolDefinition(tool.definition(), tool.providerId(), tool.title(), tool.description(), tool.tags(), tool.capability(),
                tool.inputSchema(), tool.outputSchema(), tool.configurationSchema(), tool.defaultConfiguration(), tool.capabilities(), risk,
                new ToolExecutionPolicy(policy.timeout(), policy.maxAttempts(), policy.retryBackoff(), policy.maxInputBytes(), policy.maxOutputBytes(), approval), "0".repeat(64));
        return new Fixture(original.provider(), original.schema(), new ToolDefinitionRevision(withTitle(revised, revised.title()),
                original.provider().definition(), original.revision().target()), original.adapterRef());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_" + table, Integer.class);
    }
}

package com.arte.ainew.tool.gateway;

import com.arte.ainew.application.gateway.DefaultToolGateway;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.release.LocalToolPublicationValidator;
import com.arte.ainew.application.tool.release.ToolReleaseService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisToolBindingStore;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.persistence.mybatis.MybatisToolReleaseStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
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
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 真实隔离 H2：发现／发布／固定绑定、FREE 准备后执行网关；无 HTTP 受理、外部模型或业务数据库。
 * 授权使用可控测试端口，不把它当作实际平台资源授权的验收证据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 15:21 ✾
 */
public class ToolGatewayIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");
    private final Clock clock = Clock.systemUTC();
    private final TrustedActor actor = new TrustedActor(new Principal("subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), Instant.now());
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final AtomicInteger invokes = new AtomicInteger(), authorizations = new AtomicInteger(), ids = new AtomicInteger();
    private final AtomicBoolean disabledAdapter = new AtomicBoolean();
    private final AtomicReference<LocalToolCall> received = new AtomicReference<>();
    private Fixture fixture;
    private ToolExecutionPolicy policy;
    private ToolRiskProfile risk;
    private StructuredValue.ObjectValue configuration = object();
    private Function<LocalToolCall, Mono<ToolResult>> operation = call -> Mono.just(success(call.call(), object("text", text("ok"))));
    private Function<LocalToolCall, Mono<ExecutionAuthorization>> authorization = call -> Mono.just(call.call().runtime().execution().authorization());
    private BusinessToolAdapter customAdapter;
    private Scheduler io, metadata, invocationScheduler;
    private JdbcTemplate jdbc;
    private Connection ddl;
    private MybatisToolCatalogStore catalog;
    private MybatisToolReleaseStore releases;
    private MybatisToolBindingStore bindings;
    private MybatisExecutionPersistence executions;
    private LocalToolRegistry registry;
    private LocalToolDiscovery discovery;
    private ToolBindingService service;
    private ToolReleaseService releaseService;
    private ToolBindingDefinition binding;
    private ToolBindingResolution plan;
    private DefaultToolGateway gateway;

    @Before
    public void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        ddl = source.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).populate(ddl);
        jdbc = new JdbcTemplate(source);
        io = Schedulers.newBoundedElastic(6, 64, "tool-gateway-db-test");
        metadata = Schedulers.newBoundedElastic(2, 16, "tool-gateway-metadata-test");
        invocationScheduler = Schedulers.newBoundedElastic(4, 32, "tool-gateway-invoke-test");
        catalog = new MybatisToolCatalogStore(source, codec, io, clock);
        releases = new MybatisToolReleaseStore(source, codec, io, clock);
        var limits = new ToolExecutionPolicy(Duration.ofMinutes(1), 10, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE);
        bindings = new MybatisToolBindingStore(source, codec, io, clock, limits, catalog, releases);
        executions = new MybatisExecutionPersistence(source, codec, io);
        policy = new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE);
        risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of("resource:read"), Set.of());
    }

    @After
    public void cleanup() throws Exception {
        invocationScheduler.dispose();
        metadata.dispose();
        io.dispose();
        jdbc.execute("DROP ALL OBJECTS");
        ddl.close();
    }

    private void install() {
        var base = fixture("text", "v1");
        var original = base.revision().tool();
        var tool = new ToolDefinition(original.definition(), original.providerId(), original.title(), original.description(), original.tags(),
                original.capability(), original.inputSchema(), original.outputSchema(), original.configurationSchema(), object(),
                original.capabilities(), risk, policy, "0".repeat(64));
        fixture = new Fixture(base.provider(), base.schema(), new ToolDefinitionRevision(withTitle(tool, tool.title()),
                base.provider().definition(), base.revision().target()), base.adapterRef());
        var adapter = customAdapter != null ? customAdapter : new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                var expected = fixture.revision().tool().discoveredCapability();
                return disabledAdapter.get() ? new CapabilityDescriptor(expected.definition(), expected.kind(), expected.inputSchema(), expected.outputSchema(),
                        expected.features(), expected.sideEffect(), CapabilityDescriptor.Availability.DISABLED) : expected;
            }

            public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                throw new AssertionError("Typed configured port required");
            }

            public Mono<ToolResult> invoke(LocalToolCall call) {
                invokes.incrementAndGet();
                received.set(call);
                return operation.apply(call);
            }
        };
        registry = new LocalToolRegistry(List.of(new LocalToolRegistry.Registration(fixture.provider(),
                () -> Mono.just(fixture.definitions()), Map.of(fixture.adapterRef(), adapter))));
        discovery = new LocalToolDiscovery(registry, catalog, new ToolSchemaValidator(), metadata, clock, Duration.ofSeconds(2));
        assertTrue(discovery.refresh().block(WAIT).state().discoveryHealthy());
        var validator = new LocalToolPublicationValidator(discovery, registry, new ToolSchemaValidator(), metadata, clock, Duration.ofSeconds(2));
        releaseService = new ToolReleaseService(releases, validator);
        var review = new ToolReleaseCommand.Review(List.of(new ToolReleaseCommand.Example(object(), object())), "review", null,
                policy.approvalMode(), null, null);
        var published = releaseService.execute(actor, new ToolReleaseCommand(tool.definition(), ToolReleaseCommand.Action.PUBLISH,
                0, "publish", "reviewed", review, null)).block(WAIT);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, published.value().rejection());
        var settings = new ToolBindingSettings(tool.definition(), ToolBindingSettings.VersionPolicy.PINNED, "binding", true,
                configuration, Map.of(), null, null);
        binding = bindings.execute(actor, owner, new ToolBindingCommand(ToolBindingCommand.Action.SAVE, null, 0,
                "binding", "create", settings, null, null, false)).block(WAIT).value().result().value();
        service = new ToolBindingService(bindings, discovery, registry, metadata, Duration.ofSeconds(2));
        plan = service.resolve(owner, binding.bindingId()).block(WAIT).value();
        assertNotNull(plan);
        gateway = gateway(catalog);
    }

    private DefaultToolGateway gateway(ToolCatalogStore documents) {
        return new DefaultToolGateway(service, documents, registry, call -> {
            authorizations.incrementAndGet();
            return authorization.apply(call);
        }, new ToolSchemaValidator(), codec, invocationScheduler, clock);
    }

    private GatewayCall<ToolInvocation> call() {
        return call(object(), Duration.ofSeconds(20), 1024);
    }

    private GatewayCall<ToolInvocation> call(StructuredValue.ObjectValue arguments, Duration duration, long outputBytes) {
        if (gateway == null) install();
        var at = Instant.now();
        String id = "execution-" + ids.incrementAndGet();
        var auth = new ExecutionAuthorization(new ExecutionPrincipal(owner.subjectId(), "alice", ExecutionPrincipal.Kind.USER),
                owner.tenantId(), owner.workspaceId(), Set.of("resource:read", "extra:scope"), "grant");
        var context = new ExecutionContext(id, "trace", auth, at.plus(duration), null, null, "release", "key-" + id);
        var input = new ToolInvocation("call-" + id, fixture.revision().tool().definition(), arguments);
        var request = new InvocationRequest<>(fixture.revision().tool().capability(), plan.definition(), input.kind(), input,
                new ExecutionOptions(context.deadline(), 3, outputBytes, 0, 0), context);
        var candidate = new Invocation(request, "a".repeat(64), null, null, null, Invocation.State.ACCEPTED, 0, null, null, null, at, at);
        assertTrue(executions.accept(new ExecutionCommands.Accept(candidate, null)).block(WAIT).successful());
        var claim = executions.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofMinutes(2), 1).block(WAIT).getFirst();
        var result = executions.prepareDispatch(new DispatchPreparationStore.Command(new ExecutionCommands.Version(owner, id, 0),
                claim, "attempt-" + id, Duration.ofSeconds(30), new DispatchPreparationStore.Free())).block(WAIT).value();
        return new GatewayCall<>(request, plan.resolvedBinding(), result.attempt(), ExecutionRuntimeContext.start(context));
    }

    private static ToolResult success(GatewayCall<ToolInvocation> call, StructuredValue output) {
        return new ToolResult(call.request().input().callId(), ToolResult.Outcome.SUCCEEDED, output,
                List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
    }

    private ToolResult run(GatewayCall<ToolInvocation> call) {
        return gateway.invoke(call).block(WAIT);
    }

    private void denied(ToolResult result, String code) {
        assertEquals(ToolResult.Outcome.FAILED, result.outcome());
        assertEquals(code, result.error().code());
        assertEquals(ExecutionError.SideEffect.NONE, result.sideEffect());
        assertEquals(ExecutionError.Certainty.KNOWN, result.error().certainty());
        assertEquals(0, invokes.get());
    }

    private void unknown(ToolResult result, String code) {
        assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
        assertEquals(code, result.error().code());
        assertEquals(ExecutionError.SideEffect.POSSIBLE, result.sideEffect());
        assertEquals(ExecutionError.Certainty.UNKNOWN, result.error().certainty());
        assertFalse(result.error().retryable());
        assertNull(result.output());
        assertEquals(1, invokes.get());
    }

    @Test
    public void realFixedBindingAndFreePreparationExecuteOnceWithoutAdvancingPersistence() {
        configuration = object("text", text("fixed-config"));
        var call = call();
        var result = run(call);
        assertEquals(ToolResult.Outcome.SUCCEEDED, result.outcome());
        assertEquals(configuration, received.get().binding().effectiveConfiguration());
        assertEquals(call.attempt().remoteRequestId(), received.get().call().attempt().remoteRequestId());
        assertEquals(1, invokes.get());
        assertEquals(1, authorizations.get());
        assertEquals(Invocation.State.RUNNING, executions.find(owner, call.attempt().invocationId()).block(WAIT).state());
        assertEquals(call.attempt(), executions.findAttempt(owner, call.attempt().invocationId(), call.attempt().attemptId()).block(WAIT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class).intValue());
    }

    @Test
    public void gatewayIsLazyAndTheSamePublisherCannotExecuteTwice() {
        var call = call();
        var publisher = gateway.invoke(call);
        assertEquals(0, authorizations.get());
        assertEquals(0, invokes.get());
        assertEquals(ToolResult.Outcome.SUCCEEDED, publisher.block(WAIT).outcome());
        assertThrows(IllegalStateException.class, () -> publisher.block(WAIT));
        assertEquals(1, invokes.get());
    }

    @Test
    public void invalidInputNeverReachesAuthorizationOrAdapter() {
        denied(run(call(object("unknown", text("private")), Duration.ofSeconds(20), 1024)), "TOOL_INPUT_INVALID");
        assertEquals(0, authorizations.get());
    }

    @Test
    public void utf8InputLimitRejectsMultibyteText() {
        denied(run(call(object("text", text("中".repeat(400))), Duration.ofSeconds(20), 1024)), "TOOL_INPUT_INVALID");
    }

    @Test
    public void disabledBindingRejectsFixedMaterial() {
        var call = call();
        disableBinding();
        denied(run(call), "TOOL_BINDING_UNAVAILABLE");
    }

    @Test
    public void disabledReleaseRejectsFixedMaterial() {
        var call = call();
        disableRelease();
        denied(run(call), "TOOL_BINDING_UNAVAILABLE");
    }

    @Test
    public void governanceIsRecheckedAfterAuthorizationWait() {
        var call = call();
        authorization = local -> Mono.fromCallable(() -> {
            disableBinding();
            return local.call().runtime().execution().authorization();
        });
        denied(run(call), "TOOL_BINDING_UNAVAILABLE");
    }

    @Test
    public void executorAvailabilityIsRecheckedAfterAuthorization() {
        var call = call();
        authorization = local -> {
            disabledAdapter.set(true);
            return Mono.just(local.call().runtime().execution().authorization());
        };
        denied(run(call), "TOOL_BINDING_UNAVAILABLE");
    }

    @Test
    public void permissionFailureIsKnownBeforeExecutionAndDoesNotLeakItsMessage() {
        var call = call();
        authorization = ignored -> Mono.error(new AccessDeniedException("private-resource-secret"));
        var result = run(call);
        denied(result, "TOOL_ACCESS_DENIED");
        assertFalse(codec.encode(new InvocationResult.Tool(result)).contains("private-resource-secret"));
    }

    @Test
    public void emptyAuthorizationFailsClosed() {
        var call = call();
        authorization = ignored -> Mono.empty();
        denied(run(call), "TOOL_ACCESS_DENIED");
    }

    @Test
    public void currentAuthorizationMayNarrowScopesAndReachesTheAdapter() {
        var call = call();
        authorization = local -> Mono.just(local.call().runtime().execution().authorization().restrictTo(Set.of("resource:read")));
        assertEquals(ToolResult.Outcome.SUCCEEDED, run(call).outcome());
        assertEquals(Set.of("resource:read"), received.get().call().runtime().execution().authorization().scopes());
    }

    @Test
    public void revokedRequiredScopeRejectsExecution() {
        var call = call();
        authorization = local -> Mono.just(local.call().runtime().execution().authorization().restrictTo(Set.of()));
        denied(run(call), "TOOL_ACCESS_DENIED");
    }

    @Test
    public void authorizationCannotSwitchOwnerOrWidenScopes() {
        var call = call();
        authorization = local -> Mono.just(new ExecutionAuthorization(new ExecutionPrincipal("other", "other", ExecutionPrincipal.Kind.USER),
                owner.tenantId(), owner.workspaceId(), Set.of("resource:read"), "grant"));
        denied(run(call), "TOOL_ACCESS_DENIED");
        authorization = local -> Mono.just(new ExecutionAuthorization(local.call().runtime().execution().authorization().principal(),
                owner.tenantId(), owner.workspaceId(), Set.of("resource:read", "admin:write"), "grant"));
        denied(run(call()), "TOOL_ACCESS_DENIED");
    }

    @Test
    public void writeAndApprovalRequiredToolsAreNotEnabledByPublication() {
        risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.RESOURCE_WRITE, false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of());
        policy = new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
        denied(run(call()), "TOOL_APPROVAL_UNSUPPORTED");
    }

    @Test
    public void localToolsWithNetworkDeclarationsRemainUnsupported() {
        risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE,
                Set.of(), Set.of(URI.create("https://example.invalid")));
        denied(run(call()), "TOOL_EXTERNAL_ACCESS_UNSUPPORTED");
    }

    @Test
    public void missingSchemaAndStorageFaultAreNotConfusedWithInputErrors() {
        var call = call();
        var missing = documents(Mono.empty());
        denied(gateway(missing).invoke(call).block(WAIT), "TOOL_SCHEMA_UNAVAILABLE");
        var broken = documents(Mono.error(new IllegalStateException("sql-secret")));
        var result = gateway(broken).invoke(call).block(WAIT);
        denied(result, "TOOL_PREFLIGHT_ERROR");
        assertFalse(codec.encode(new InvocationResult.Tool(result)).contains("sql-secret"));
    }

    @Test
    public void alreadyCancelledCallNeverEntersAdapter() {
        var call = call();
        call.runtime().cancellation().cancel("cancelled");
        denied(run(call), "TOOL_CANCELLED");
    }

    @Test
    public void cancellationDuringAuthorizationPreventsLateEntry() throws Exception {
        var call = call();
        var started = new CountDownLatch(1);
        var authorizationResult = Sinks.<ExecutionAuthorization>one();
        authorization = local -> {
            started.countDown();
            return authorizationResult.asMono();
        };
        var future = gateway.invoke(call).toFuture();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        call.runtime().cancellation().cancel("cancelled");
        denied(future.get(3, TimeUnit.SECONDS), "TOOL_CANCELLED");
        authorizationResult.tryEmitValue(call.runtime().execution().authorization());
        assertEquals(0, invokes.get());
    }

    @Test
    public void cancellationAfterAdapterEntryIsUnknownAndCancelsItsPublisher() throws Exception {
        var call = call();
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        operation = local -> {
            started.countDown();
            return Mono.<ToolResult>never().doOnCancel(stopped::countDown);
        };
        var future = gateway.invoke(call).toFuture();
        assertTrue(started.await(3, TimeUnit.SECONDS));
        call.runtime().cancellation().cancel("cancelled");
        unknown(future.get(3, TimeUnit.SECONDS), "TOOL_CANCELLED");
        assertTrue(stopped.await(3, TimeUnit.SECONDS));
    }

    @Test
    public void deadlineAfterAdapterEntryIsUnknownWithoutRetry() {
        operation = local -> Mono.never();
        unknown(run(call(object(), Duration.ofMillis(500), 1024)), "TOOL_DEADLINE_EXCEEDED");
    }

    @Test
    public void fixedPolicyDeadlineAlsoCoversAuthorizationWait() {
        policy = new ToolExecutionPolicy(Duration.ofSeconds(1), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE);
        authorization = local -> Mono.never();
        denied(run(call()), "TOOL_DEADLINE_EXCEEDED");
    }

    @Test
    public void synchronousAdapterExceptionIsUnknownAndSafe() {
        operation = local -> {
            throw new IllegalStateException("private-adapter-secret");
        };
        var result = run(call());
        unknown(result, "TOOL_INVOCATION_ERROR");
        assertFalse(codec.encode(new InvocationResult.Tool(result)).contains("private-adapter-secret"));
    }

    @Test
    public void asynchronousAdapterExceptionIsUnknown() {
        operation = local -> Mono.error(new IllegalStateException("private"));
        unknown(run(call()), "TOOL_INVOCATION_ERROR");
    }

    @Test
    public void emptyAdapterResultCannotBecomeSuccessful() {
        operation = local -> Mono.empty();
        unknown(run(call()), "TOOL_RESULT_EMPTY");
    }

    @Test
    public void mismatchedCallIdDiscardsUntrustedOutput() {
        operation = local -> Mono.just(new ToolResult("wrong-call", ToolResult.Outcome.SUCCEEDED, object(), List.of(), List.of(),
                ExecutionError.SideEffect.NONE, null));
        unknown(run(call()), "TOOL_RESULT_INVALID");
    }

    @Test
    public void undeclaredSideEffectDoesNotBecomeKnownReadOnlySuccess() {
        operation = local -> Mono.just(new ToolResult(local.call().request().input().callId(), ToolResult.Outcome.SUCCEEDED, object(),
                List.of(), List.of(), ExecutionError.SideEffect.CONFIRMED, null));
        unknown(run(call()), "TOOL_RESULT_INVALID");
    }

    @Test
    public void outputSchemaIsValidatedAfterInvocation() {
        operation = local -> Mono.just(success(local.call(), object("text", new StructuredValue.NumberValue(BigDecimal.ONE))));
        unknown(run(call()), "TOOL_OUTPUT_INVALID");
    }

    @Test
    public void serializedEnvelopeLimitIncludesUtf8AndMetadata() {
        operation = local -> Mono.just(success(local.call(), object("text", text("中".repeat(100)))));
        var call = call();
        var sample = codec.encode(new InvocationResult.Tool(success(call, object("text", text("中".repeat(100))))));
        var limited = new InvocationRequest<>(call.request().capability(), call.request().binding(), call.request().kind(), call.request().input(),
                new ExecutionOptions(call.request().options().deadline(), 3, sample.length(), 0, 0), call.request().context());
        unknown(run(new GatewayCall<>(limited, call.binding(), call.attempt(), call.runtime())), "TOOL_OUTPUT_LIMIT");
    }

    @Test
    public void declaredKnownFailureIsPreserved() {
        operation = local -> Mono.just(new ToolResult(local.call().request().input().callId(), ToolResult.Outcome.FAILED, null, List.of(), List.of(),
                ExecutionError.SideEffect.NONE, new ExecutionError("DOMAIN_DENIED", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace")));
        var result = run(call());
        assertEquals(ToolResult.Outcome.FAILED, result.outcome());
        assertEquals("DOMAIN_DENIED", result.error().code());
        assertEquals(1, invokes.get());
    }

    @Test
    public void declaredUnknownFactsAreNotDowngradedToKnownFailure() {
        operation = local -> Mono.just(new ToolResult(local.call().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(),
                ExecutionError.SideEffect.NONE, new ExecutionError("DOMAIN_UNCERTAIN", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.UNKNOWN, "trace")));
        var result = run(call());
        assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
        assertEquals("DOMAIN_UNCERTAIN", result.error().code());
        assertEquals(ExecutionError.SideEffect.NONE, result.sideEffect());
        assertEquals(1, invokes.get());
    }

    @Test
    public void nullAdapterPublisherIsUnknown() {
        operation = local -> null;
        unknown(run(call()), "TOOL_INVOCATION_ERROR");
    }

    @Test
    public void legacyAdapterAcceptsOnlyEmptyConfiguration() {
        customAdapter = legacyAdapter();
        assertEquals(ToolResult.Outcome.SUCCEEDED, run(call()).outcome());
        assertEquals(1, invokes.get());
    }

    @Test
    public void legacyAdapterCannotSilentlyIgnoreConfiguredValues() {
        customAdapter = legacyAdapter();
        configuration = object("text", text("configured"));
        denied(run(call()), "TOOL_CONFIGURATION_UNSUPPORTED");
    }

    @Test
    public void fixedBindingCannotBeUsedForAnotherToolOrRemoteTarget() {
        var call = call();
        var request = new InvocationRequest<>(call.request().capability(), call.request().binding(), call.request().kind(),
                new ToolInvocation(call.request().input().callId(), ref("tool", "other.count", "v1"), object()),
                call.request().options(), call.request().context());
        var mismatched = new GatewayCall<>(request, call.binding(), call.attempt(), call.runtime());
        assertThrows(IllegalArgumentException.class, () -> new LocalToolCall(mismatched, plan));
        denied(run(mismatched), "TOOL_BINDING_MISMATCH");
        var remote = new ResolvedBinding(call.binding().definition(), call.binding().capability(),
                new ExecutionTarget.Remote(ref("connection", "connection", "v1"), "operation"), null, null);
        denied(run(new GatewayCall<>(call.request(), remote, call.attempt(), call.runtime())), "TOOL_BINDING_MISMATCH");
    }

    @Test
    public void budgetBoundInvocationCannotEnterTheFreeLocalGateway() {
        var call = call();
        var before = call.runtime().execution();
        var context = new ExecutionContext(before.executionId(), before.traceId(), before.authorization(), before.deadline(),
                null, "paid-budget", before.releaseRef(), before.idempotencyKey());
        var request = new InvocationRequest<>(call.request().capability(), call.request().binding(), call.request().kind(), call.request().input(),
                call.request().options(), context);
        denied(run(new GatewayCall<>(request, call.binding(), call.attempt(), ExecutionRuntimeContext.start(context))), "TOOL_CHARGE_UNSUPPORTED");
    }

    @Test
    public void runtimeCannotChangeTheAcceptedOperationKey() {
        var call = call();
        var before = call.runtime().execution();
        var changed = new ExecutionContext(before.executionId(), before.traceId(), before.authorization(), before.deadline(),
                before.parentExecutionId(), before.budgetRef(), before.releaseRef(), "changed-key");
        var different = new GatewayCall<>(call.request(), call.binding(), call.attempt(), ExecutionRuntimeContext.start(changed));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolCall(different, plan));
        denied(run(different), "TOOL_PREFLIGHT_ERROR");
    }

    @Test
    public void expiredRuntimeDeadlineNeverEntersAdapter() {
        var call = call();
        var expired = new DefaultToolGateway(service, catalog, registry, local -> Mono.just(local.call().runtime().execution().authorization()),
                new ToolSchemaValidator(), codec, invocationScheduler, Clock.offset(clock, Duration.ofMinutes(1)));
        denied(expired.invoke(call).block(WAIT), "TOOL_DEADLINE_EXCEEDED");
    }

    @Test
    public void alreadyExpiredAttemptCannotReachAuthorizationOrAdapter() {
        var call = call();
        var before = call.attempt();
        var expired = new Attempt(before.attemptId(), before.invocationId(), before.attemptNumber(), before.workerId(), before.fencingToken(),
                Instant.now().minusSeconds(1), before.version(), before.state(), before.dispatch(), before.remoteRequestId(),
                before.budgetReservationId(), before.usage(), before.error(), before.createdAt().minusSeconds(60), before.updatedAt());
        denied(run(new GatewayCall<>(call.request(), call.binding(), expired, call.runtime())), "TOOL_ATTEMPT_LEASE_EXPIRED");
        assertEquals(0, authorizations.get());
    }

    @Test
    public void synchronousBlockingAdapterDoesNotBlockDeadlineWatchdog() {
        operation = local -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Mono.just(success(local.call(), object()));
        };
        unknown(run(call(object(), Duration.ofMillis(500), 1024)), "TOOL_DEADLINE_EXCEEDED");
    }

    @Test
    public void outputEncodingFailureIsUnknownInsteadOfSuccessfulOrUnexecuted() {
        var call = call();
        var broken = new ExecutionRecordCodec() {
            public String encode(Object value) {
                throw new IllegalStateException("private-codec-error");
            }

            public <T> T decode(String json, Class<T> type) {
                throw new AssertionError("No decode");
            }
        };
        var gateway = new DefaultToolGateway(service, catalog, registry, local -> Mono.just(local.call().runtime().execution().authorization()),
                new ToolSchemaValidator(), broken, invocationScheduler, clock);
        unknown(gateway.invoke(call).block(WAIT), "TOOL_INVOCATION_ERROR");
    }

    private BusinessToolAdapter legacyAdapter() {
        return new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                return fixture.revision().tool().discoveredCapability();
            }

            public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                invokes.incrementAndGet();
                return Mono.just(success(call, object()));
            }
        };
    }

    private ToolCatalogStore documents(Mono<ToolSchemaDefinition> response) {
        return (ToolCatalogStore) Proxy.newProxyInstance(ToolCatalogStore.class.getClassLoader(), new Class<?>[]{ToolCatalogStore.class},
                (proxy, method, args) -> method.getName().equals("findSchema") ? response : method.invoke(catalog, args));
    }

    private void disableBinding() {
        assertTrue(bindings.execute(actor, owner, new ToolBindingCommand(ToolBindingCommand.Action.SET_ENABLED, binding.bindingId(),
                binding.version(), "disable-binding", "stop", null, false, null, false)).block(WAIT).value().result().successful());
    }

    private void disableRelease() {
        var current = releases.find(fixture.revision().tool().definition()).block(WAIT);
        assertEquals(ToolReleaseReceipt.Rejection.NONE, releaseService.execute(actor, new ToolReleaseCommand(current.tool(),
                ToolReleaseCommand.Action.DISABLE, current.version(), "disable-release", "stop", null, null)).block(WAIT).value().rejection());
    }
}

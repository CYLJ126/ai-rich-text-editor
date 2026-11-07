package com.arte.ainew.admission;

import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.infrastructure.control.ConfigurationAiAdmissionAuthorizationGuard;
import com.arte.ainew.infrastructure.control.ControlConfigurationReader;
import com.arte.ainew.infrastructure.control.ExecutionPolicies;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.testing.TestAuthorizationResolver;
import com.arte.ainew.testing.TestAuthorizations;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 真实 H2 事务验证：异步预检允许不替代受理／派发写入时的当前政策。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public class ExecutionPolicyGuardIntegrationTest {

    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture f;
    private final Clock clock = Clock.systemUTC();
    private final AtomicReference<AiExecutionPolicy> current = new AtomicReference<>();
    private final AtomicInteger transactionChecks = new AtomicInteger();
    private AiAdmissionAuthorizationGuard guard;
    private final AiExecutionPolicyProvider policies = new AiExecutionPolicyProvider() {
        public Mono<AiExecutionPolicy> current(ExecutionContext context) {
            return Mono.fromSupplier(current::get);
        }

        public AiExecutionPolicy inCurrentTransaction(ExecutionContext context) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            transactionChecks.incrementAndGet();
            return current.get();
        }
    };

    @Before
    public void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        scheduler = Schedulers.newBoundedElastic(4, 128, "policy-guard-test");
        f = new AdmissionFixture(source, scheduler);
        current.set(ExecutionPolicies.from(f.properties.limits()));
        var authorization = TestAuthorizations.create(new TestAuthorizationResolver(f.properties), f.properties, clock);
        guard = new ConfigurationAiAdmissionAuthorizationGuard(new ControlConfigurationReader(f.properties.source(), reactor.core.scheduler.Schedulers.immediate()), authorization,
                f.catalog.entitlementAdapter(), f.catalog.readAdapter(), policies, f.properties.limits(), clock);
        f.initializeBudget("alice");
    }

    @After
    public void close() {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
    }

    private AiExecutionPolicy tightened() {
        var p = current.get();
        return new AiExecutionPolicy(p.maxInputBytes(), 64, p.maxOutputBytes(), p.maximumTimeout(), p.snapshotRetention());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    public void tighteningAfterPreflightRollsBackAcceptanceAndTurnWithoutDispatchWork() {
        var authorization = TestAuthorizations.create(new TestAuthorizationResolver(f.properties), f.properties, clock);
        AiAdmissionAuthorizationGuard racing = check -> guard.authorize(check).map(permit -> actual -> {
            current.set(tightened());
            permit.requireInCurrentTransaction(actual);
        });
        var coordinator = new AdmissionInvocationCoordinator(authorization, f.catalog, f.catalog, f.executions, f.payloads, f.executions, f.properties.deployment(), clock, policies, racing, f.catalog.readAdapter(), f.catalog.readAdapter());
        var chat = new DefaultChatService(authorization, f.conversations, f.catalog, f.contexts, coordinator, clock, f.executions, f.executions, f.properties.deployment());
        var conversation = f.conversations.create("policy", null, List.of(), f.context("alice", "create")).block();
        var context = f.context("alice", "submit");
        var error = assertThrows(AdmissionException.class, () -> chat.submit(f.chatRequest(conversation.conversationId(), 0, "hello", context), context).block());
        assertEquals(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED, error.getResultCode());
        assertEquals(1, transactionChecks.get());
        assertEquals(0, count("arte_ai_invocation"));
        assertEquals(0, count("arte_ai_turn"));
        assertEquals(0, count("arte_ai_outbox"));
        assertEquals(0, f.conversations.find(conversation.conversationId(), f.context("alice", "read")).block().version());
    }

    private Invocation accepted() {
        var conversation = f.conversations.create("policy", null, List.of(), f.context("alice", "create")).block();
        var context = f.context("alice", "submit");
        var accepted = f.chat.submit(f.chatRequest(conversation.conversationId(), 0, "hello", context), context).block();
        return f.executions.find(AdmissionFixture.owner("alice-id"), accepted.executionId()).block();
    }

    private AiAdmissionAuthorizationGuard.Check check(Invocation invocation, AiAdmissionAuthorizationGuard.Boundary boundary) {
        return new AiAdmissionAuthorizationGuard.Check(invocation.request(), f.properties.bindings().getFirst(),
                f.payloads.find(AdmissionFixture.owner("alice-id"), invocation.contextSnapshotId()).block(), null, boundary);
    }

    @Test
    public void tighteningBetweenDispatchPreflightAndTransactionCreatesNeitherAttemptNorReservation() {
        var invocation = accepted();
        var check = check(invocation, AiAdmissionAuthorizationGuard.Boundary.DISPATCH);
        var permit = guard.authorize(check).block();
        current.set(tightened());
        var claim = f.executions.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofMinutes(1), 1).block().getFirst();
        var command = new DispatchPreparationStore.Command(new ExecutionCommands.Version(claim.owner(), claim.invocationId(), invocation.version()),
                claim, "attempt", Duration.ofSeconds(30), AdmissionFixture.money("1"), AdmissionFixture.RATE, Duration.ofMinutes(5));
        assertThrows(AdmissionException.class, () -> f.executions.prepareDispatch(command,
                () -> permit.requireInCurrentTransaction(invocation.request())).block());
        assertEquals(1, transactionChecks.get());
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(0, count("arte_ai_reservation"));
        assertEquals(Invocation.State.ACCEPTED, f.executions.find(claim.owner(), claim.invocationId()).block().state());
        assertEquals(AdmissionFixture.money("0"), f.executions.account(claim.owner(), "alice-budget").block().held());
    }

    @Test
    public void permitRejectsMissingOrReadOnlyTransactionAndDifferentRequest() {
        var invocation = accepted();
        var permit = guard.authorize(check(invocation, AiAdmissionAuthorizationGuard.Boundary.ADMISSION)).block();
        assertThrows(IllegalStateException.class, () -> permit.requireInCurrentTransaction(invocation.request()));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            permit.requireInCurrentTransaction(invocation.request());
            return null;
        }));
        var r = invocation.request();
        var other = new InvocationRequest<>(r.capability(), r.binding(), r.kind(), r.input(), r.options(), f.context("alice", "other"));
        assertThrows(AccessDeniedException.class, () -> permit.requireInCurrentTransaction(other));
        transaction.setReadOnly(false);
        transaction.execute(status -> {
            permit.requireInCurrentTransaction(r);
            return null;
        });
        assertEquals(1, transactionChecks.get());
    }

    @Test
    public void externalSendRechecksCurrentPolicyAfterAnEarlierPermitAllowedIt() {
        var invocation = accepted();
        var check = check(invocation, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND);
        assertNotNull(guard.authorize(check).block().connection());
        current.set(tightened());
        var error = assertThrows(AdmissionException.class, () -> guard.authorize(check).block());
        assertEquals(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED, error.getResultCode());
        assertEquals(0, count("arte_ai_attempt"));
    }

    @Test
    public void providerCannotExpandDeploymentCeiling() {
        current.set(new AiExecutionPolicy(1_000_000, 1000, 1_000_000, Duration.ofMinutes(10), Duration.ofDays(1)));
        var invocation = accepted();
        assertNotNull(guard.authorize(check(invocation, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block());
        var authorization = com.arte.ainew.testing.TestAuthorizations.create(new TestAuthorizationResolver(f.properties), f.properties, clock);
        var gateway = new com.arte.ainew.spi.gateway.ModelGateway() {
            public boolean supportsTextChat(com.arte.ainew.pojo.control.ResolvedBinding binding, com.arte.ainew.pojo.control.ConnectionDefinition connection) {
                return true;
            }

            public reactor.core.publisher.Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                throw new AssertionError("Discovery must not send");
            }
        };
        var discovery = new com.arte.ainew.application.control.ChatConfigurationQueryService(f.properties.deployment(), authorization, f.catalog.entitlementAdapter(), f.catalog.readAdapter(), f.catalog.readAdapter(), () -> gateway, policies);
        var context = f.context("alice", "discover");
        var limit = discovery.discover(context).block().options().getFirst().limits();
        assertEquals(f.properties.limits().maxOutputTokens(), limit.maxOutputTokens());
        assertEquals(f.properties.limits().maxInputBytes(), limit.maxInputBytes());
        assertEquals(f.properties.limits().maximumTimeout().toSeconds(), limit.maxTimeoutSeconds());
        current.set(tightened());
        assertEquals(64, discovery.discover(context).block().options().getFirst().limits().maxOutputTokens());
    }

    @Test
    public void allExecutionLimitsAreCheckedAgainAgainstTheSavedRequest() {
        var invocation = accepted();
        var p = current.get();
        var variants = List.of(
                new AiExecutionPolicy(1, p.maxOutputTokens(), p.maxOutputBytes(), p.maximumTimeout(), p.snapshotRetention()),
                tightened(),
                new AiExecutionPolicy(p.maxInputBytes(), p.maxOutputTokens(), 1, p.maximumTimeout(), p.snapshotRetention()),
                new AiExecutionPolicy(p.maxInputBytes(), p.maxOutputTokens(), p.maxOutputBytes(), Duration.ofSeconds(1), p.snapshotRetention()),
                new AiExecutionPolicy(p.maxInputBytes(), p.maxOutputTokens(), p.maxOutputBytes(), p.maximumTimeout(), p.maximumTimeout()));
        for (var policy : variants) {
            current.set(policy);
            assertThrows(AdmissionException.class, () -> guard.authorize(check(invocation, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block());
        }
    }

    @Test
    public void asyncOnlyPolicyCannotAuthorizeTheWriteTransaction() {
        var invocation = accepted();
        var authorization = com.arte.ainew.testing.TestAuthorizations.create(new TestAuthorizationResolver(f.properties), f.properties, clock);
        var asyncOnly = new ConfigurationAiAdmissionAuthorizationGuard(new ControlConfigurationReader(f.properties.source(), reactor.core.scheduler.Schedulers.immediate()), authorization,
                f.catalog.entitlementAdapter(), f.catalog.readAdapter(), context -> Mono.just(current.get()), f.properties.limits(), clock);
        var permit = asyncOnly.authorize(check(invocation, AiAdmissionAuthorizationGuard.Boundary.ADMISSION)).block();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(UnsupportedOperationException.class, () -> transaction.execute(status -> {
            permit.requireInCurrentTransaction(invocation.request());
            return null;
        }));
    }

    @Test
    public void currentSourceIsSharedOnlyWithinOneCallbackAndClearedAfterFailure() {
        var reads = new AtomicInteger();
        var context = f.context("alice", "current-source");
        var configuration = new AtomicReference<>(f.properties);
        var authority = new com.arte.ainew.spi.auth.AiControlConfigurationSource() {
            public ControlConfiguration configuration(ExecutionContext ignored) {
                return f.properties.snapshot();
            }

            public ControlConfiguration configurationInCurrentTransaction(ExecutionContext ignored) {
                reads.incrementAndGet();
                return configuration.get().snapshot();
            }

            public ControlConfiguration definitions() {
                return f.properties.snapshot();
            }

            public com.arte.ainew.pojo.control.ControlConfiguration.Grant grant(ExecutionContext ignored) {
                throw new AssertionError();
            }
        };
        var first = new ControlConfigurationReader(authority, scheduler);
        var second = new ControlConfigurationReader(authority, scheduler);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        // 读范围持有旧配置；事务当前读必须绕过它，并仅在本次同步回调复用来源。
        first.inSnapshot(context, old -> {
            var p = f.properties;
            configuration.set(new com.arte.ainew.testing.TestAiConfiguration(p.enabled(), p.dataSourceBean(), p.releaseRef(), p.persistence(),
                    ExecutionPolicies.legacy(tightened()), p.grants(), p.capabilities(), p.bindings(), p.connections(), p.rates(), p.budgets()));
            return Mono.fromCallable(() -> transaction.execute(status -> first.inCurrentTransaction(context, value -> {
                assertNotSame(old, value);
                assertSame(value, second.inCurrentTransaction(context, nested -> nested));
                assertEquals(64, value.limits().maxOutputTokens());
                return value;
            })));
        }).block();
        assertEquals(1, reads.get());
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> first.inCurrentTransaction(context, value -> {
            throw new IllegalStateException("rollback");
        })));
        transaction.execute(status -> first.inCurrentTransaction(context, value -> value));
        assertEquals(3, reads.get());
    }

    @Test
    public void suspendedTransactionCannotLendItsCurrentFactsToAnotherTransaction() {
        var reader = new ControlConfigurationReader(f.properties.source(), reactor.core.scheduler.Schedulers.immediate());
        var context = f.context("alice", "suspended");
        var manager = new DataSourceTransactionManager(source);
        var outer = new TransactionTemplate(manager);
        var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.execute(status -> reader.inCurrentTransaction(context, value -> {
            assertThrows(AccessDeniedException.class, () -> inner.execute(other -> reader.inCurrentTransaction(context, nested -> nested)));
            assertSame(value, reader.inCurrentTransaction(context, nested -> nested));
            return null;
        }));
    }
}

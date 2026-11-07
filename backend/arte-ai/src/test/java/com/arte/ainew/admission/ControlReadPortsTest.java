package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ControlPreparation;
import com.arte.ainew.infrastructure.control.SourceControlReadAdapter;
import com.arte.ainew.pojo.control.ControlConfiguration;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.control.BudgetDefinitionProvider.Operation;
import com.arte.ainew.testing.TestAiConfiguration;
import com.arte.ainew.testing.TestAuthorizationResolver;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ControlReadPortsTest {

    private final TestAiConfiguration properties = AdmissionFixture.properties();
    private final TestAuthorizationResolver resolver = new TestAuthorizationResolver(properties);
    private final AdmissionAuthorization authorization = com.arte.ainew.testing.TestAuthorizations.create(resolver, properties, Clock.systemUTC());
    private static final Duration WAIT = Duration.ofSeconds(5);

    private ExecutionContext context(Set<String> scopes) {
        var auth = resolver.resolve("alice", "tenant", "workspace", scopes).block(WAIT);
        return new ExecutionContext("read-execution", "read-trace", auth, Instant.now().plusSeconds(60),
                "alice-budget", null, properties.releaseRef(), null);
    }

    private TestAiConfiguration changedBudget() {
        return new TestAiConfiguration(properties.enabled(), properties.dataSourceBean(), properties.releaseRef(),
                properties.persistence(), properties.limits(), properties.grants(), properties.capabilities(), properties.bindings(),
                properties.connections(), properties.rates(), List.of(new ControlConfiguration.Budget("alice-budget",
                AdmissionFixture.owner("alice-id"), AdmissionFixture.money("200"), AdmissionFixture.RATE), properties.budgets().get(1)));
    }

    private final class Source implements AiControlConfigurationSource {
        TestAiConfiguration current = properties;
        boolean revoked;
        final AtomicInteger reads = new AtomicInteger();

        public ControlConfiguration configuration(ExecutionContext context) {
            reads.incrementAndGet();
            if (revoked) throw new AccessDeniedException("revoked");
            return (current).snapshot();
        }

        public ControlConfiguration definitions() {
            return (properties).snapshot();
        }

        public ControlConfiguration.Grant grant(ExecutionContext context) {
            throw new AssertionError("Must reuse snapshot grant");
        }
    }

    @Test
    public void fixedDefinitionsAreExactImmutableAndBudgetOperationsStaySeparate() {
        var adapter = new SourceControlReadAdapter(properties.source(), Schedulers.immediate(), authorization);
        var context = context(AdmissionFixture.REGULAR);
        var published = adapter.read(context).block(WAIT);
        assertEquals(AdmissionFixture.CONNECTION, published.connections().getFirst().definition());
        assertEquals(AdmissionFixture.BINDING, adapter.binding(AdmissionFixture.BINDING, context).block(WAIT).definition());
        assertEquals(AdmissionFixture.CAP, adapter.capability(AdmissionFixture.CAP, context).block(WAIT).definition());
        assertEquals(AdmissionFixture.CONNECTION, adapter.connection(AdmissionFixture.CONNECTION, context).block(WAIT).definition());
        assertEquals(AdmissionFixture.money("1"), adapter.rate(AdmissionFixture.RATE, context).block(WAIT).inputPerMillion());
        assertThrows(UnsupportedOperationException.class, () -> published.rates().clear());
        assertThrows(com.arte.ainew.application.support.AdmissionException.class,
                () -> adapter.rate(new DefinitionRef("rate", "text", "v2"), context).block(WAIT));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("rate", "text", "latest"));
        assertEquals(List.of("alice-budget"), adapter.available(context, Operation.READ).block(WAIT).stream().map(b -> b.budgetRef()).toList());
        assertThrows(com.arte.ainew.application.support.AdmissionException.class,
                () -> adapter.resolve("bob-budget", context, Operation.READ).block(WAIT));
        assertThrows(AccessDeniedException.class, () -> adapter.available(context, Operation.INITIALIZE).block(WAIT));
        assertEquals("alice-budget", adapter.resolve("alice-budget", context(Set.of(AdmissionAuthorization.BUDGET_ADMIN)), Operation.INITIALIZE).block(WAIT).budgetRef());
    }

    @Test
    public void differentAdaptersShareOneOperationSnapshotAndRefreshOnNextSubscription() {
        var source = new Source();
        var catalog = new SourceControlReadAdapter(source, Schedulers.immediate(), authorization);
        var budgets = new SourceControlReadAdapter(source, Schedulers.immediate(), authorization);
        var context = context(AdmissionFixture.REGULAR);
        var operation = catalog.inSnapshot(context, published -> {
            source.current = changedBudget();
            return budgets.resolve("alice-budget", context, Operation.READ);
        });
        assertEquals(AdmissionFixture.money("100"), operation.block(WAIT).limit());
        assertEquals(1, source.reads.get());
        assertEquals(AdmissionFixture.money("200"), operation.block(WAIT).limit());
        assertEquals(2, source.reads.get());
    }

    @Test
    public void anotherSourceCannotBorrowTheOperationSnapshot() {
        var first = new SourceControlReadAdapter(new Source(), Schedulers.immediate(), authorization);
        var second = new SourceControlReadAdapter(new Source(), Schedulers.immediate(), authorization);
        var context = context(AdmissionFixture.REGULAR);
        assertThrows(AccessDeniedException.class, () -> first.inSnapshot(context, ignored -> second.read(context)).block(WAIT));
    }

    @Test
    public void readScopeCannotAuthorizeWritesOrBorrowAnotherExecution() {
        var source = new Source();
        var auth = com.arte.ainew.testing.TestAuthorizations.create(resolver, properties, Clock.systemUTC(), source, Schedulers.immediate());
        var adapter = new SourceControlReadAdapter(source, Schedulers.immediate(), auth);
        var context = context(AdmissionFixture.REGULAR);
        assertThrows(AccessDeniedException.class, () -> adapter.inSnapshot(context, ignored ->
                adapter.read(context.child("other", context.deadline(), context.authorization().scopes(), null))).block(WAIT));
        source.reads.set(0);
        assertThrows(AccessDeniedException.class, () -> adapter.inSnapshot(context, ignored -> {
            source.revoked = true;
            return auth.prepare(context, Set.of(AdmissionAuthorization.INVOKE), false, Mono::just);
        }).block(WAIT));
        assertEquals(2, source.reads.get());
    }

    @Test
    public void newWritePreparationOverridesAnOuterReadSnapshot() {
        var source = new Source();
        var adapter = new SourceControlReadAdapter(source, Schedulers.immediate(), authorization);
        var context = context(AdmissionFixture.REGULAR);
        var budget = adapter.inSnapshot(context, ignored -> ControlPreparation.use(context, changedBudget().snapshot(),
                current -> adapter.resolve("alice-budget", current, Operation.READ))).block(WAIT);
        assertEquals(AdmissionFixture.money("200"), budget.limit());
        assertEquals(1, source.reads.get());
    }

    @Test
    public void historicalRateDoesNotRequireAnActiveGrantAndNeverSubstitutesAnotherVersion() {
        var source = new Source();
        var adapter = new SourceControlReadAdapter(source, Schedulers.immediate(), authorization);
        source.revoked = true;
        assertEquals(AdmissionFixture.RATE, adapter.historicalRate(AdmissionFixture.RATE).block(WAIT).definition());
        assertEquals(0, source.reads.get());
        assertThrows(com.arte.ainew.application.support.AdmissionException.class,
                () -> adapter.historicalRate(new DefinitionRef("rate", "text", "v2")).block(WAIT));
    }

    @Test
    public void aGrantCannotExposeAForeignOwnedBudgetOrCreateAccountAuthority() {
        var grant = properties.grants().getFirst();
        var borrowed = new ControlConfiguration.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(),
                grant.workspaceId(), grant.grantRef(), true, grant.scopes(), grant.bindingIds(), Set.of("bob-budget"));
        var wrongOwner = new TestAiConfiguration(false, properties.dataSourceBean(), properties.releaseRef(), properties.persistence(),
                properties.limits(), List.of(borrowed), properties.capabilities(), properties.bindings(), properties.connections(), properties.rates(), properties.budgets());
        var adapter = new SourceControlReadAdapter(wrongOwner.source(), Schedulers.immediate(), authorization);
        assertTrue(adapter.available(context(AdmissionFixture.REGULAR), Operation.READ).block(WAIT).isEmpty());
    }
}

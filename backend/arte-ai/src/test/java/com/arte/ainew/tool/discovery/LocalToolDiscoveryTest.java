package com.arte.ainew.tool.discovery;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.discovery.ToolStartupDiscovery;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.arte.ainew.application.tool.discovery.LocalToolDiscovery.Code.*;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 验证显式登记、完整快照、安全失败及并发边界，不以工具实际执行替代发现验收。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public class LocalToolDiscoveryTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T01:02:03.123456Z"), ZoneOffset.UTC);
    private final Fixture first = fixture("text", "v1");
    private Scheduler scheduler;
    private MemoryStore store;

    @Before
    public void setup() {
        scheduler = Schedulers.newBoundedElastic(2, 16, "tool-discovery-test");
        store = new MemoryStore();
    }

    @After
    public void cleanup() {
        scheduler.dispose();
    }

    private LocalToolDiscovery service(LocalToolRegistry.Registration... registrations) {
        return new LocalToolDiscovery(new LocalToolRegistry(List.of(registrations)), store, new ToolSchemaValidator(), scheduler, CLOCK, Duration.ofSeconds(2));
    }

    @Test
    public void startupDiscoveryIsBoundedFailClosedAndDoesNotPublish() {
        var discovery = service(first.registration());
        var lifecycle = new ToolStartupDiscovery(discovery);
        assertFalse(lifecycle.isRunning());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        assertTrue(discovery.state().discoveryHealthy());
        assertEquals(LocalToolDiscovery.manifest(new ToolCatalogStore.Batch(List.of(first.provider()),
                first.definitions().schemas(), List.of(first.revision()))), discovery.state().snapshot().checksum());
        lifecycle.start();
        assertEquals(1, store.calls.get());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
        var failed = new ToolStartupDiscovery(service(first.registration(() -> Mono.error(new IllegalStateException("source failed")))));
        assertThrows(IllegalStateException.class, failed::start);
        assertFalse(failed.isRunning());
    }

    @Test
    public void constructionAndUnsubscribedRefreshHaveNoSideEffects() {
        var calls = new AtomicInteger();
        var discovery = service(first.registration(() -> {
            calls.incrementAndGet();
            return Mono.just(first.definitions());
        }));
        var pending = discovery.refresh();
        assertEquals(0, calls.get());
        assertEquals(0, store.calls.get());
        assertFalse(discovery.state().discoveryHealthy());
        assertNull(discovery.state().snapshot());
        assertEquals(UNINITIALIZED, discovery.state().lastResult());
        assertEquals(APPLIED, pending.block(WAIT).code());
        assertEquals(1, calls.get());
    }

    @Test
    public void successInstallsOnlyDiscoveredImmutableMetadata() {
        var discovery = service(first.registration());
        var result = discovery.refresh().block(WAIT);
        assertEquals(APPLIED, result.code());
        var snapshot = result.state().snapshot();
        assertTrue(result.state().discoveryHealthy());
        assertEquals(CLOCK.instant(), snapshot.loadedAt());
        assertEquals(first.provider(), snapshot.providers().get(first.provider().definition()));
        assertEquals(first.revision(), snapshot.tools().get(first.revision().tool().definition()));
        assertEquals(CapabilityDescriptor.Availability.DISCOVERED, snapshot.tools().values().iterator().next().tool().discoveredCapability().availability());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.tools().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.schemas().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.providers().clear());
        assertEquals(REPLAYED, discovery.refresh().block(WAIT).code());
    }

    @Test
    public void multiSubscriberRefreshIsOneCommandButNewCallsRefreshAgain() {
        var reads = new AtomicInteger();
        var discovery = service(first.registration(() -> {
            reads.incrementAndGet();
            return Mono.just(first.definitions());
        }));
        var command = discovery.refresh();
        var result = Mono.zip(command, command).block(WAIT);
        assertSame(result.getT1(), result.getT2());
        assertEquals(1, reads.get());
        discovery.refresh().block(WAIT);
        assertEquals(2, reads.get());
        assertEquals(2, store.calls.get());
    }

    @Test
    public void allSourcesMustCompleteBeforeAnyStoreWriteOrViewReplacement() {
        var gate = Sinks.<LocalToolDefinitionSource.Definitions>one();
        var second = fixture("other", "v1");
        var entered = new CountDownLatch(1);
        var discovery = service(first.registration(), second.registration(() -> {
            entered.countDown();
            return gate.asMono();
        }));
        var pending = discovery.refresh().toFuture();
        await(entered);
        assertEquals(0, store.calls.get());
        assertNull(discovery.state().snapshot());
        gate.tryEmitValue(second.definitions());
        assertEquals(APPLIED, pending.join().code());
        assertEquals(1, store.calls.get());
        assertEquals(2, discovery.state().snapshot().tools().size());
    }

    @Test
    public void oneFailedSourceDoesNotPersistEarlierSuccessfulSources() {
        var second = fixture("other", "v1");
        var discovery = service(first.registration(), second.registration(() -> Mono.error(new IllegalStateException("private-config"))));
        assertEquals(SOURCE_FAILED, discovery.refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
        assertNull(discovery.state().snapshot());
    }

    @Test
    public void failedRefreshRetainsTheWholePreviousSnapshotAndRecoversExplicitly() {
        var broken = new AtomicReference<RuntimeException>();
        var discovery = service(first.registration(() -> broken.get() == null ? Mono.just(first.definitions()) : Mono.error(broken.get())));
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        broken.set(new IllegalStateException("private-token"));
        var result = discovery.refresh().block(WAIT);
        assertEquals(SOURCE_FAILED, result.code());
        assertSame(previous, result.state().snapshot());
        assertFalse(result.state().discoveryHealthy());
        assertFalse(result.toString().contains("private-token"));
        assertEquals(1, store.calls.get());
        broken.set(null);
        assertEquals(REPLAYED, discovery.refresh().block(WAIT).code());
        assertTrue(discovery.state().discoveryHealthy());
    }

    @Test
    public void emptyPublisherIsNotACompleteEmptySource() {
        var discovery = service(first.registration(Mono::empty));
        assertEquals(SOURCE_EMPTY, discovery.refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
    }

    @Test
    public void neverEndingSourceTimesOutWithoutLosingOldMetadata() {
        var waiting = new AtomicReference<Boolean>(false);
        var discovery = new LocalToolDiscovery(new LocalToolRegistry(List.of(first.registration(() -> waiting.get() ? Mono.never() : Mono.just(first.definitions())))),
                store, new ToolSchemaValidator(), scheduler, CLOCK, Duration.ofMillis(100));
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        waiting.set(true);
        assertEquals(SOURCE_TIMEOUT, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void sourceRunsOnTheDedicatedSchedulerInsteadOfTheSubscriberThread() {
        var thread = new AtomicReference<String>();
        var discovery = service(first.registration(() -> {
            thread.set(Thread.currentThread().getName());
            return Mono.just(first.definitions());
        }));
        discovery.refresh().block(WAIT);
        assertTrue(thread.get(), thread.get().startsWith("tool-discovery-test"));
    }

    @Test
    public void concurrentRefreshCannotOvertakeTheEarlierFullSnapshot() {
        var gate = Sinks.<LocalToolDefinitionSource.Definitions>one();
        var entered = new CountDownLatch(1);
        var discovery = service(first.registration(() -> {
            entered.countDown();
            return gate.asMono();
        }));
        var pending = discovery.refresh().toFuture();
        await(entered);
        var state = discovery.state();
        assertEquals(BUSY, discovery.refresh().block(WAIT).code());
        assertSame(state, discovery.state());
        gate.tryEmitValue(first.definitions());
        assertEquals(APPLIED, pending.join().code());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void leavingTheSubscriberDoesNotReleaseAnInFlightWrite() {
        var gate = Sinks.<StoreOutcome<ToolCatalogStore.Batch>>one();
        var entered = new CountDownLatch(1);
        var written = new AtomicReference<ToolCatalogStore.Batch>();
        store.handler = batch -> {
            written.set(batch);
            entered.countDown();
            return gate.asMono();
        };
        var discovery = service(first.registration());
        var command = discovery.refresh();
        var subscription = command.subscribe();
        await(entered);
        subscription.dispose();
        assertEquals(BUSY, discovery.refresh().block(WAIT).code());
        assertNull(discovery.state().snapshot());
        gate.tryEmitValue(StoreOutcome.applied(written.get()));
        assertEquals(APPLIED, command.block(WAIT).code());
        assertTrue(discovery.state().discoveryHealthy());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void sameVersionDriftKeepsOldSnapshotAndMarksDiscoveryUnhealthy() {
        var definitions = new AtomicReference<>(first.definitions());
        var discovery = service(first.registration(() -> Mono.just(definitions.get())));
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        var changed = new ToolDefinitionRevision(withTitle(first.revision().tool(), "changed"), first.provider().definition(), first.revision().target());
        definitions.set(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(changed)));
        assertEquals(CONTRACT_DRIFT, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        assertFalse(discovery.state().discoveryHealthy());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void storageDriftDetectedAfterRestartAlsoRejectsTheWholeView() {
        service(first.registration()).refresh().block(WAIT);
        var changed = new ToolDefinitionRevision(withTitle(first.revision().tool(), "changed"), first.provider().definition(), first.revision().target());
        var restarted = service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(changed)))));
        assertEquals(CONTRACT_DRIFT, restarted.refresh().block(WAIT).code());
        assertNull(restarted.state().snapshot());
    }

    @Test
    public void aNewToolVersionReplacesTheViewWithoutMutatingHistoricalRecords() {
        var second = fixture("text", "v2");
        var definitions = new AtomicReference<>(first.definitions());
        var registration = new LocalToolRegistry.Registration(first.provider(), () -> Mono.just(definitions.get()), Map.of(
                first.adapterRef(), adapter(first.revision().tool().discoveredCapability()), second.adapterRef(), adapter(second.revision().tool().discoveredCapability())));
        var discovery = service(registration);
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        definitions.set(second.definitions());
        assertEquals(APPLIED, discovery.refresh().block(WAIT).code());
        assertEquals(Set.of(second.revision().tool().definition()), discovery.state().snapshot().tools().keySet());
        assertEquals(Set.of(first.revision().tool().definition()), previous.tools().keySet());
        assertTrue(store.history.containsKey(first.revision().tool().definition()));
    }

    @Test
    public void explicitlyEmptyCompleteSourceRemovesOnlyTheCurrentViewEntries() {
        var definitions = new AtomicReference<>(first.definitions());
        var discovery = service(first.registration(() -> Mono.just(definitions.get())));
        discovery.refresh().block(WAIT);
        definitions.set(new LocalToolDefinitionSource.Definitions(List.of(), List.of()));
        assertEquals(REPLAYED, discovery.refresh().block(WAIT).code());
        assertTrue(discovery.state().snapshot().tools().isEmpty());
        assertTrue(store.history.containsKey(first.revision().tool().definition()));
    }

    @Test
    public void missingImplementationIsNeverDiscoveredAsAvailable() {
        var registration = new LocalToolRegistry.Registration(first.provider(), () -> Mono.just(first.definitions()), Map.of());
        assertEquals(ADAPTER_MISMATCH, service(registration).refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
    }

    @Test
    public void capabilityMismatchAndDisabledImplementationAreRejected() {
        var wrong = fixture("other", "v1").revision().tool().discoveredCapability();
        for (var actual : List.of(wrong, new CapabilityDescriptor(first.revision().tool().capability(), CapabilityDescriptor.Kind.TOOL,
                first.schema().definition(), first.schema().definition(), Set.of(), CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.DISABLED))) {
            var registration = new LocalToolRegistry.Registration(first.provider(), () -> Mono.just(first.definitions()), Map.of(first.adapterRef(), adapter(actual)));
            assertEquals(ADAPTER_MISMATCH, service(registration).refresh().block(WAIT).code());
        }
        assertEquals(0, store.calls.get());
    }

    @Test
    public void anExecutableAdapterDeclarationDoesNotPublishTheDiscoveredTool() {
        var expected = first.revision().tool().discoveredCapability();
        var actual = new CapabilityDescriptor(expected.definition(), expected.kind(), expected.inputSchema(), expected.outputSchema(), expected.features(),
                expected.sideEffect(), CapabilityDescriptor.Availability.EXECUTABLE);
        var registration = new LocalToolRegistry.Registration(first.provider(), () -> Mono.just(first.definitions()), Map.of(first.adapterRef(), adapter(actual)));
        var discovery = service(registration);
        assertEquals(APPLIED, discovery.refresh().block(WAIT).code());
        assertEquals(CapabilityDescriptor.Availability.DISCOVERED,
                discovery.state().snapshot().tools().get(first.revision().tool().definition()).tool().discoveredCapability().availability());
    }

    @Test
    public void remoteTargetsAndToolsFromAnotherProviderCannotEnterALocalSource() {
        var remote = new ToolDefinitionRevision(first.revision().tool(), first.provider().definition(),
                new ExecutionTarget.Remote(ref("connection", "remote", "v1"), "count"));
        assertEquals(ADAPTER_MISMATCH, service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(remote)))))
                .refresh().block(WAIT).code());
        var otherProvider = new ToolDefinitionRevision(first.revision().tool(), ref("tool-provider", "text", "v2"), first.revision().target());
        assertEquals(INVALID_DEFINITION, service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(otherProvider)))))
                .refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
    }

    @Test
    public void schemasMustBePresentInTheCompleteCandidateNotOnlyInStorage() {
        service(first.registration()).refresh().block(WAIT);
        var discovery = service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(), List.of(first.revision())))));
        assertEquals(INVALID_DEFINITION, discovery.refresh().block(WAIT).code());
        assertNull(discovery.state().snapshot());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void schemaDigestUnsupportedKeywordsAndWrongInputRootAreRejectedBeforeStorage() {
        var badDigest = new ToolSchemaDefinition(first.schema().definition(), first.schema().document(), "0".repeat(64));
        var unsupported = schema(first.schema().definition(), object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"), "format", text("custom")));
        var wrongRoot = schema(first.schema().definition(), object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("string")));
        for (var schema : List.of(badDigest, unsupported, wrongRoot)) {
            var discovery = service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(schema), List.of(first.revision())))));
            assertEquals(INVALID_DEFINITION, discovery.refresh().block(WAIT).code());
        }
        assertEquals(0, store.calls.get());
    }

    @Test
    public void invalidDefaultConfigurationIsRejectedBeforeStorage() {
        var required = schema(first.schema().definition(), object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"),
                "properties", object("text", object("type", text("string"))),
                "required", new com.arte.ainew.common.value.StructuredValue.ArrayValue(List.of(text("text")))));
        assertEquals(INVALID_DEFINITION, service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(required), List.of(first.revision())))))
                .refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
    }

    @Test
    public void storageFailureOrRejectionRetainsThePreviousView() {
        var discovery = service(first.registration());
        discovery.refresh().block(WAIT);
        var previous = discovery.state().snapshot();
        store.handler = batch -> Mono.error(new IllegalStateException("jdbc-secret"));
        assertEquals(STORE_FAILED, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        store.handler = batch -> Mono.just(StoreOutcome.rejected(StoreOutcome.Code.NOT_FOUND));
        assertEquals(STORE_REJECTED, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        store.handler = batch -> Mono.empty();
        assertEquals(STORE_FAILED, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
    }

    @Test
    public void manifestIsIndependentOfSourceOrderAndIncludesExecutionTarget() {
        var other = fixture("other", "v1");
        var one = service(first.registration(), other.registration());
        var two = service(other.registration(), first.registration());
        one.refresh().block(WAIT);
        two.refresh().block(WAIT);
        assertEquals(one.state().snapshot().checksum(), two.state().snapshot().checksum());
        var newAdapter = ref("tool-adapter", "different", "v1");
        var changed = new ToolDefinitionRevision(first.revision().tool(), first.provider().definition(), new ExecutionTarget.Local(newAdapter));
        var isolatedStore = new MemoryStore();
        var registration = new LocalToolRegistry.Registration(first.provider(), () -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(changed))),
                Map.of(newAdapter, adapter(first.revision().tool().discoveredCapability())));
        var isolated = new LocalToolDiscovery(new LocalToolRegistry(List.of(registration, other.registration())), isolatedStore, new ToolSchemaValidator(), scheduler, CLOCK, Duration.ofSeconds(2));
        isolated.refresh().block(WAIT);
        assertNotEquals(one.state().snapshot().checksum(), isolated.state().snapshot().checksum());
    }

    @Test
    public void registrationRequiresUniqueExplicitLocalProvidersAndTypedAdapterReferences() {
        var registry = new LocalToolRegistry(List.of(first.registration()));
        assertNotNull(registry.findAdapter(first.adapterRef()).orElseThrow());
        assertTrue(registry.findAdapter(ref("tool-adapter", "missing", "v1")).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> registry.registrations().clear());
        assertThrows(IllegalArgumentException.class, () -> new LocalToolRegistry(List.of(first.registration(), first.registration())));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolRegistry(List.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.findAdapter(ref("connection", "wrong", "v1")));
        var remote = new ToolProviderDefinition(first.provider().definition(), ToolProviderDefinition.Kind.HTTP,
                first.provider().adapter(), ref("connection", "remote", "v1"));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolRegistry.Registration(remote, Mono::empty, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolRegistry.Registration(first.provider(), Mono::empty,
                Map.of(ref("connection", "wrong", "v1"), adapter(first.revision().tool().discoveredCapability()))));
    }

    @Test
    public void definitionCollectionsAreDefensiveAndBounded() {
        var tools = new ArrayList<>(List.of(first.revision()));
        var definitions = new LocalToolDefinitionSource.Definitions(List.of(first.schema()), tools);
        tools.clear();
        assertEquals(1, definitions.tools().size());
        assertThrows(UnsupportedOperationException.class, () -> definitions.tools().clear());
        assertThrows(IllegalArgumentException.class, () -> new LocalToolDefinitionSource.Definitions(List.of(first.schema(), first.schema()), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolDefinitionSource.Definitions(List.of(), Collections.nCopies(129, first.revision())));
    }

    @Test
    public void combinedLimitStopsBeforeReadingLaterSourcesOrWritingAnything() {
        var oversized = new ArrayList<LocalToolRegistry.Registration>();
        for (String provider : List.of("one", "two")) {
            var fixture = fixture(provider, "v1");
            var tools = new ArrayList<ToolDefinitionRevision>();
            for (int i = 0; i < 65; i++) tools.add(fixture(provider, "v" + i).revision());
            // 合并容量先于执行器匹配检查；第二份来源使完整候选超过 128 项。
            oversized.add(new LocalToolRegistry.Registration(fixture.provider(),
                    () -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(fixture.schema()), tools)), Map.of()));
        }
        var laterReads = new AtomicInteger();
        oversized.add(first.registration(() -> {
            laterReads.incrementAndGet();
            return Mono.just(first.definitions());
        }));
        var discovery = service(oversized.toArray(LocalToolRegistry.Registration[]::new));
        assertEquals(INVALID_DEFINITION, discovery.refresh().block(WAIT).code());
        assertEquals(0, laterReads.get());
        assertEquals(0, store.calls.get());
    }

    @Test
    public void sharedSchemaIsDeduplicatedButContradictoryContentRejectsTheWholeCandidate() {
        var other = fixture("other", "v1");
        var documents = new AtomicReference<>(List.of(other.schema(), first.schema()));
        var discovery = service(first.registration(), other.registration(() -> Mono.just(
                new LocalToolDefinitionSource.Definitions(documents.get(), List.of(other.revision())))));
        assertEquals(APPLIED, discovery.refresh().block(WAIT).code());
        assertEquals(2, discovery.state().snapshot().schemas().size());
        var previous = discovery.state().snapshot();
        documents.set(List.of(other.schema(), schema(first.schema().definition(), object("$schema", text(ToolSchemaValidator.DIALECT), "type", text("string")))));
        assertEquals(CONTRACT_DRIFT, discovery.refresh().block(WAIT).code());
        assertSame(previous, discovery.state().snapshot());
        assertEquals(1, store.calls.get());
    }

    @Test
    public void duplicateAdapterReferenceCannotBeOwnedByTwoRegistrations() {
        var other = fixture("other", "v1");
        var collision = new LocalToolRegistry.Registration(other.provider(), Mono::empty,
                Map.of(first.adapterRef(), adapter(other.revision().tool().discoveredCapability())));
        assertThrows(IllegalArgumentException.class, () -> new LocalToolRegistry(List.of(first.registration(), collision)));
    }

    @Test
    public void matchingCapabilityDoesNotAllowAForgedToolDigest() {
        var original = first.revision().tool();
        var forged = new com.arte.ainew.pojo.tool.ToolDefinition(original.definition(), original.providerId(), original.title(), original.description(),
                original.tags(), original.capability(), original.inputSchema(), original.outputSchema(), original.configurationSchema(), original.defaultConfiguration(),
                original.capabilities(), original.risk(), original.defaultPolicy(), "0".repeat(64));
        var revision = new ToolDefinitionRevision(forged, first.provider().definition(), first.revision().target());
        var discovery = service(first.registration(() -> Mono.just(new LocalToolDefinitionSource.Definitions(List.of(first.schema()), List.of(revision)))));
        assertEquals(INVALID_DEFINITION, discovery.refresh().block(WAIT).code());
        assertEquals(0, store.calls.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * 只用于发现服务的故障注入；真实 SQL 原子性另由集成测试验证。
     */
    static final class MemoryStore implements ToolCatalogStore {
        final AtomicInteger calls = new AtomicInteger();
        final Map<DefinitionRef, Object> history = new HashMap<>();
        volatile Function<Batch, Mono<StoreOutcome<Batch>>> handler;

        public Mono<StoreOutcome<Batch>> put(Batch batch) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                if (handler != null) return handler.apply(batch);
                var incoming = new HashMap<DefinitionRef, Object>();
                batch.providers().forEach(p -> incoming.put(p.definition(), p));
                batch.schemas().forEach(s -> incoming.put(s.definition(), s));
                batch.tools().forEach(t -> incoming.put(t.tool().definition(), t));
                for (var entry : incoming.entrySet())
                    if (history.containsKey(entry.getKey()) && !history.get(entry.getKey()).equals(entry.getValue()))
                        return Mono.just(StoreOutcome.rejected(StoreOutcome.Code.IDEMPOTENCY_CONFLICT));
                boolean replayed = history.keySet().containsAll(incoming.keySet());
                history.putAll(incoming);
                return Mono.just(replayed ? StoreOutcome.replayed(batch) : StoreOutcome.applied(batch));
            });
        }

        public Mono<ToolProviderDefinition> findProvider(DefinitionRef provider) {
            throw new AssertionError("No discovery by database paging");
        }

        public Mono<ToolSchemaDefinition> findSchema(DefinitionRef schema) {
            throw new AssertionError("Candidate must be complete");
        }

        public Mono<ToolDefinitionRevision> findTool(DefinitionRef tool) {
            throw new AssertionError("Candidate must be complete");
        }

        public Mono<Page<ToolProviderDefinition>> listProviders(String afterKey, int limit) {
            throw new AssertionError("No discovery by database paging");
        }

        public Mono<Page<ToolDefinitionRevision>> listTools(DefinitionRef provider, String afterKey, int limit) {
            throw new AssertionError("No discovery by database paging");
        }
    }
}

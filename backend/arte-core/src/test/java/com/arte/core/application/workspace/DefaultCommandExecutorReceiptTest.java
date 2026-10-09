package com.arte.core.application.workspace;

import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.NOW;
import static com.arte.core.application.workspace.InMemoryWorkspaceFixture.meta;
import static org.junit.jupiter.api.Assertions.*;

class DefaultCommandExecutorReceiptTest {
    private static final CommandScope SCOPE = new CommandScope("tenant", null);
    private static final String TYPE = "tenant:member:remove";
    private static final String SCHEMA = "void-result:v1";
    private static final CommandMeta META = meta("remove-member");
    private static final List<String> FIELDS = List.of("target-member", "0");
    private final InMemoryWorkspaceFixture f = new InMemoryWorkspaceFixture();
    private final TrustedActor alice = f.user("alice");
    private final AtomicInteger ids = new AtomicInteger();
    private final DefaultCommandExecutor executor = new DefaultCommandExecutor(f.transactions,
            new CurrentPrincipalResolver(f.identity), f.receiptStore, () -> "operation-" + ids.incrementAndGet(), f.clock);

    @Test
    void voidCommandAppliesOnceAndReplaysNullWithOriginalReceiptFacts() {
        var executions = new AtomicInteger();
        var replayChecks = new AtomicInteger();
        Consumer<Void> authorize = value -> {
            assertNull(value);
            f.transactions.requireActive();
            replayChecks.incrementAndGet();
        };
        Function<String, DefaultCommandExecutor.Result<Void>> work = operationId -> {
            f.transactions.requireActive();
            executions.incrementAndGet();
            return new DefaultCommandExecutor.Result<>(null, 6);
        };
        CommandReceipt<Void> first = execute(Void.class, authorize, work);
        CommandReceipt<Void> replay = execute(Void.class, authorize, work);
        assertEquals(CommandStatusEnum.APPLIED, first.status());
        assertEquals(CommandDeliveryEnum.APPLIED, first.delivery());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.resultVersion(), replay.resultVersion());
        assertEquals(first.acceptedAt(), replay.acceptedAt());
        assertEquals(first.completedAt(), replay.completedAt());
        assertNull(first.value());
        assertNull(replay.value());
        assertEquals(1, executions.get());
        assertEquals(1, replayChecks.get());
        assertEquals(1, ids.get());
        assertEquals(1, f.receipts.size());
    }

    @Test
    void voidReplayStillRejectsDifferentRequestUnderSameOperationKey() {
        execute(Void.class, value -> fail("首次执行不应重放授权"), id -> new DefaultCommandExecutor.Result<>(null, 0));
        var error = assertThrows(PlatformException.class, () -> executor.execute(alice, META, SCOPE, TYPE, SCHEMA,
                Void.class, List.of("different-member", "0"), this::lock,
                value -> fail("摘要冲突不应进入重放授权"), id -> fail("摘要冲突不应重复执行")));
        assertEquals(PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT, error.getCode());
        assertEquals(1, f.receipts.size());
    }

    @Test
    void voidReplayRequiresCurrentAuthorizationEvenWithoutResultView() {
        var first = execute(Void.class, value -> {
        }, id -> new DefaultCommandExecutor.Result<>(null, 0));
        var checks = new AtomicInteger();
        var error = assertThrows(PlatformException.class, () -> execute(Void.class, value -> {
            assertNull(value);
            checks.incrementAndGet();
            throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, META.traceId());
        }, id -> fail("重放拒绝后不应再次执行")));
        assertEquals(PlatformErrorCodeEnum.ACCESS_DENIED, error.getCode());
        assertEquals(1, checks.get());
        assertEquals(first, f.receipts.get(identity()).receipt());
    }

    @Test
    void voidReplayRechecksLatestIdentityWhenOrdinaryReadStillShowsActive() {
        execute(Void.class, value -> {
        }, id -> new DefaultCommandExecutor.Result<>(null, 0));
        f.captureOldReadSnapshot();
        f.identities.put("alice", new Principal("alice", alice.principal().kind(), PrincipalStatusEnum.SUSPENDED));
        var error = assertThrows(PlatformException.class, () -> execute(Void.class,
                value -> fail("停用身份不应进入重放授权"), id -> fail("停用身份不应执行")));
        assertEquals(PlatformErrorCodeEnum.ACCESS_DENIED, error.getCode());
    }

    @ParameterizedTest
    @MethodSource("invalidStoredValues")
    <T> void malformedStoredResultIsRejectedWithoutAuthorizationOrExecution(Class<T> resultType, Object value) {
        saveReceipt(value, SCHEMA, CommandStatusEnum.APPLIED);
        var error = assertThrows(PlatformException.class, () -> execute(resultType,
                result -> fail("损坏回执不应进入重放授权"), id -> fail("损坏回执不应重复执行")));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, error.getCode());
        assertEquals(1, f.receipts.size());
        assertEquals(0, ids.get());
    }

    private static Stream<Arguments> invalidStoredValues() {
        return Stream.of(Arguments.of(Void.class, "unexpected-payload"),
                Arguments.of(String.class, null), Arguments.of(String.class, 42));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-schema", "pending"})
    void nullVoidValueDoesNotBypassSchemaOrStatusValidation(String invalid) {
        saveReceipt(null, invalid.equals("unknown-schema") ? "unknown-result:v1" : SCHEMA,
                invalid.equals("pending") ? CommandStatusEnum.PENDING : CommandStatusEnum.APPLIED);
        var error = assertThrows(PlatformException.class, () -> execute(Void.class,
                result -> fail("无效回执不应进入重放授权"), id -> fail("无效回执不应重复执行")));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, error.getCode());
    }

    @Test
    void nonVoidCommandStillAcceptsAssignableResultAndReplaysOnce() {
        var executions = new AtomicInteger();
        var first = execute(Number.class, value -> {
        }, id -> {
            executions.incrementAndGet();
            return new DefaultCommandExecutor.Result<>(Integer.valueOf(42), 1);
        });
        var replay = execute(Number.class, value -> assertEquals(42, value), id -> fail("不应再次执行"));
        assertEquals(42, first.value());
        assertEquals(first.value(), replay.value());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(1, executions.get());
    }

    @Test
    void nullNonVoidFirstResultRollsBackDomainWriteWithoutSavingReceipt() {
        var error = assertThrows(PlatformException.class, () -> execute(String.class, value -> {
        }, id -> {
            writeTenant();
            return new DefaultCommandExecutor.Result<>(null, 0);
        }));
        assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, error.getCode());
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.receipts.isEmpty());
    }

    @Test
    void voidReceiptInsertConflictRollsBackDomainWriteAndAllowsRetry() {
        f.failReceipt = true;
        Function<String, DefaultCommandExecutor.Result<Void>> work = id -> {
            writeTenant();
            return new DefaultCommandExecutor.Result<>(null, 0);
        };
        var error = assertThrows(PlatformException.class, () -> execute(Void.class, value -> {
        }, work));
        assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, error.getCode());
        assertTrue(f.tenants.isEmpty());
        assertTrue(f.receipts.isEmpty());
        f.failReceipt = false;
        var first = execute(Void.class, value -> {
        }, work);
        var replay = execute(Void.class, value -> {
        }, id -> fail("重放不应再次写入"));
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(1, f.tenants.size());
        assertEquals(1, f.receipts.size());
    }

    private <T> CommandReceipt<T> execute(Class<T> resultType, Consumer<T> authorize,
                                          Function<String, DefaultCommandExecutor.Result<T>> work) {
        return executor.execute(alice, META, SCOPE, TYPE, SCHEMA, resultType, FIELDS, this::lock, authorize, work);
    }

    private void lock() {
        f.preferenceStore.lockSubject("alice");
    }

    private CommandIdentity identity() {
        return new CommandIdentity("alice", SCOPE, TYPE, META.operationKey());
    }

    private void saveReceipt(Object value, String schema, CommandStatusEnum status) {
        var receipt = new CommandReceipt<>("saved-operation", status, CommandDeliveryEnum.APPLIED, value, 0, NOW,
                status == CommandStatusEnum.APPLIED ? NOW : null);
        f.receipts.put(identity(), new StoredCommandReceipt(identity(), DefaultCommandExecutor.digest(FIELDS), schema, receipt, 0));
    }

    private void writeTenant() {
        assertTrue(f.tenantStore.insert(new Tenant("tenant", "测试租户", TenantTypeEnum.ORGANIZATION, null,
                TenantStateEnum.ACTIVE, "alice", 0, 0, NOW, NOW)));
    }
}
